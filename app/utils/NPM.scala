package utils

import java.io.InputStream
import java.net.{URI, URL}
import java.util.zip.GZIPInputStream
import javax.inject.Inject

import play.api.data.validation.ValidationError
import play.api.http.Status
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.libs.ws.WSClient
import utils.PackageInfo._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class NPM @Inject() (ws: WSClient, git: Git, licenseDetector: LicenseDetector, gitHub: GitHub) (implicit ec: ExecutionContext) {

  val BASE_URL = "http://registry.npmjs.org"

  // a whole lot of WTF
  private def registryMetadataUrl(packageName: String, maybeVersion: Option[String] = None): String = {
    maybeVersion.fold {
      // when a version is not specified an @ must not be encoded
      val encodedPackageName = packageName.replaceAllLiterally("/", "%2F")
      s"$BASE_URL/$encodedPackageName"
    } { version =>
      // when a version is specified an @ must be encoded
      val encodedPackageName = packageName.replaceAllLiterally("/", "%2F").replaceAllLiterally("@", "%40")
      s"$BASE_URL/$encodedPackageName/$version"
    }
  }

  private def isScoped(maybeScopeAndPackageName: String): Boolean = {
    maybeScopeAndPackageName.contains('/') && maybeScopeAndPackageName.startsWith("@")
  }

  private def registryTgzUrl(maybeScopeAndPackageName: String, version: String): String = {
    if (isScoped(maybeScopeAndPackageName)) {
      val parts = maybeScopeAndPackageName.split('/')
      val scope = parts.head
      val packageName = parts.last
      s"$BASE_URL/$scope/$packageName/-/$packageName-$version.tgz"
    }
    else {
      s"$BASE_URL/$maybeScopeAndPackageName/-/$maybeScopeAndPackageName-$version.tgz"
    }
  }

  def versions(packageNameOrGitRepo: String): Future[Seq[String]] = {
    if (git.isGit(packageNameOrGitRepo)) {
      git.versions(packageNameOrGitRepo)
    }
    else {
      ws.url(registryMetadataUrl(packageNameOrGitRepo)).get().flatMap { response =>
        response.status match {
          case Status.OK =>
            val versions = (response.json \ "versions").as[Map[String, JsObject]].keys.toIndexedSeq.sorted(VersionOrdering).reverse
            Future.successful(versions)
          case _ => Future.failed(new Exception(response.body))
        }
      }
    }
  }

  def versionsOnBranch(gitRepo: String, branch: String): Future[Seq[String]] = {
    git.gitUrl(gitRepo).flatMap(git.versionsOnBranch(_, branch))
  }

  def info(packageNameOrGitRepo: String, maybeVersion: Option[String] = None, maybeSourceUri: Option[URI] = None): Future[PackageInfo[NPM]] = {

    def packageInfo(packageJson: JsValue): Future[PackageInfo[NPM]] = {

      val maybeForkPackageJsonFuture = if (git.isGit(packageNameOrGitRepo)) {
        // this is a git repo so its package.json values might be wrong
        git.gitUrl(packageNameOrGitRepo).map { gitUrl =>
          // replace the repository.url with the possible fork's git url
          val json = packageJson.as[JsObject] ++ Json.obj("repository" -> Json.obj("url" -> gitUrl))

          // todo: resolve differences between json & maybeVersion

          json

        }
      }
      else {
        Future.successful(packageJson)
      }

      maybeForkPackageJsonFuture.flatMap { maybeForkPackageJson =>

        // allow override of the source uri
        val maybeSourceOverridePackageJson = maybeSourceUri.fold(maybeForkPackageJson) { sourceUri =>
          maybeForkPackageJson.as[JsObject] ++ Json.obj("repository" -> Json.obj("url" -> sourceUri))
        }

        maybeSourceOverridePackageJson.validate[PackageInfo[NPM]] match {

          case JsSuccess(initialInfo, _) =>

            val dependenciesSansOptionals = initialInfo.dependencies.filterKeys(initialInfo.optionalDependencies.get(_).isEmpty)

            val infoWithResolvedOptionalDependencies = initialInfo.copy[NPM](dependencies = dependenciesSansOptionals)

            infoWithResolvedOptionalDependencies.maybeGitHubUrl.fold(Future.successful(infoWithResolvedOptionalDependencies)) { gitHubUrl =>
                gitHub.currentUrls(gitHubUrl).map {
                  case (homepage, sourceConnectionUri, issuesUrl) =>
                    infoWithResolvedOptionalDependencies.copy[NPM](
                      maybeHomepageUrl = Some(homepage),
                      sourceConnectionUri = sourceConnectionUri,
                      maybeIssuesUrl = Some(issuesUrl)
                    )
                } recover {
                  // todo: fugly
                  case error: ServerError if error.status == Status.NOT_FOUND && maybeSourceUri.isDefined =>
                    infoWithResolvedOptionalDependencies.copy[NPM](
                      maybeHomepageUrl = None,
                      sourceConnectionUri = maybeSourceUri.get,
                      maybeIssuesUrl = None
                    )
                }
            }

          case JsError(errors) =>
            Future.failed[PackageInfo[NPM]](MissingMetadataException(maybeSourceOverridePackageJson, errors))
        }
      }
    }

    if (git.isGit(packageNameOrGitRepo)) {
      versions(packageNameOrGitRepo).flatMap { versions =>
        // if version was set use it, otherwise use the latest version
        val version = maybeVersion.orElse(versions.headOption)
        git.file(packageNameOrGitRepo, version, "package.json").flatMap { packageJsonString =>
          packageInfo(Json.parse(packageJsonString))
        }
      }

    }
    else {
      if (isScoped(packageNameOrGitRepo)) {
        // can no longer get info on specific versions of scoped packages
        // so get the info for all the versions and then get the specific version out of the full list
        ws.url(registryMetadataUrl(packageNameOrGitRepo)).get().flatMap { response =>
          response.status match {
            case Status.OK =>
              val maybeVersionOrLatest = maybeVersion.orElse((response.json \ "dist-tags" \ "latest").asOpt[String])
              maybeVersionOrLatest.fold(Future.failed[PackageInfo[NPM]](new Exception("Could not determine the version to get"))) { versionOrLatest =>
                val versionInfoLookup = response.json \ "versions" \ versionOrLatest
                versionInfoLookup.toOption.fold(Future.failed[PackageInfo[NPM]](new Exception(s"Could not parse: ${response.body}")))(packageInfo)
              }
            case _ =>
              Future.failed(new Exception(response.body))
          }
        }
      }
      else {
        ws.url(registryMetadataUrl(packageNameOrGitRepo, maybeVersion)).get().flatMap { response =>
          response.status match {
            case Status.OK =>
              packageInfo(response.json)
            case _ =>
              Future.failed(new Exception(response.body))
          }
        }
      }
    }
  }

  def archive(packageNameOrGitRepo: String, version: String): Future[InputStream] = {
    if (git.isGit(packageNameOrGitRepo)) {
      git.tar(packageNameOrGitRepo, Some(version), Set("node_modules"))
    }
    else {
      Future.fromTry {
        Try {
          val url = new URL(registryTgzUrl(packageNameOrGitRepo, version))
          val inputStream = url.openConnection().getInputStream
          val gzipInputStream = new GZIPInputStream(inputStream)
          gzipInputStream
        }
      }
    }
  }

}

object NPM {

  def uriIsh(repository: String): String = {
    if (repository.contains("://")) {
      // ssh://host.xz/another/repo.git
      // git://host.xz/another/repo.git
      // https://host.xz/another/repo.git
      repository
    }
    else if (repository.startsWith("gist:")) {
      // gist:11081aaa281
      repository.replaceAllLiterally("gist:", "https://gist.github.com/") + ".git"
    }
    else if (repository.startsWith("bitbucket:")) {
      // bitbucket:example/repo
      repository.replaceAllLiterally("bitbucket:", "https://bitbucket.org/") + ".git"
    }
    else if (repository.startsWith("gitlab:")) {
      // gitlab:another/repo
      repository.replaceAllLiterally("gitlab:", "https://gitlab.com/") + ".git"
    }
    else if (repository.contains(":/")) {
      // host.xz:/another/repo.git
      // user@host.xz:/another/repo.git
      "ssh://" + repository
    }
    else if (repository.contains(":")) {
      // host.xz:another/repo.git
      "ssh://" + repository.replaceAllLiterally(":", "/")
    }
    else if (repository.contains("/")) {
      // another/repo
      "https://github.com/" + repository + ".git"
    }
    else {
      repository
    }
  }

  def repositoryUrlToJsString(repositoryUrl: String): JsString = JsString(uriIsh(repositoryUrl))

  def repositoryToUri(uriIsh: String): Option[URI] = PackageInfo.readsUri.reads(repositoryUrlToJsString(uriIsh)).asOpt

  val homepageReader: Reads[Option[URL]] = {
    (__ \ "homepage").readNullable[URL]
  }

  val homepageToIssuesReader: Reads[URL] = {

    def issuesUrl(url: URL): Option[URL] = GitHub.gitHubIssuesUrl(url).orElse(Bitbucket.bitbucketIssuesUrl(url)).toOption

    val error = JsonValidationError("Could not figure out the issues URL.")

    homepageReader.collect(error) {
      // todo: nasty
      case Some(url) if issuesUrl(url).isDefined => issuesUrl(url).get
    }
  }

  val bugsReaderNullable: Reads[Option[URL]] = {
    Reads.optionNoError {
      (__ \ "bugs").read[URL]
        .orElse((__ \ "bugs" \ "url").read[URL])
        .orElse(homepageToIssuesReader)
    }
  }

  implicit val jsonReads: Reads[PackageInfo[NPM]] = {
    val repositoryUrlReader: Reads[String] = (__ \ "repository").read[String].orElse((__ \ "repository" \ "url").read[String])

    val sourceConnectionUriReader: Reads[URI] = repositoryUrlReader.map(repositoryUrlToJsString).andThen(PackageInfo.readsUri)

    val licenseReader = (__ \ "license").read[Seq[String]]
      .orElse((__ \ "license").read[String].map(Seq(_)))
      .orElse((__ \ "license").read[JsObject].map(_.\("type").as[String]).map(Seq(_)))
      .orElse((__ \ "licenses").read[Seq[JsObject]].map(_.map(_.\("type").as[String])))
      .orElse(Reads.pure(Seq.empty[String]))

    val nameReader = (__ \ "name").read[String]

    (
      nameReader ~
      (__ \ "version").read[String] ~
      homepageReader ~
      sourceConnectionUriReader ~
      bugsReaderNullable ~
      licenseReader ~
      (__ \ "dependencies").read[Map[String, String]].orElse(Reads.pure(Map.empty[String, String])) ~
      (__ \ "optionalDependencies").read[Map[String, String]].orElse(Reads.pure(Map.empty[String, String]))
    )(PackageInfo.apply[NPM] _)
  }

  val groupId: String = "org.webjars.npm"

  def deployable(npm: NPM): Deployable[NPM] = new Deployable[NPM] {
    override val name: String = "NPM"

    override val groupId: String = NPM.groupId

    override val excludes: Set[String] = Set("node_modules")

    override val metadataFile: String = "package.json"

    override val contentsInSubdir: Boolean = true

    override def archive(nameOrUrlish: String, version: String): Future[InputStream] = npm.archive(nameOrUrlish, version)

    override def info(nameOrUrlish: String, maybeVersion: Option[String], maybeSourceUri: Option[URI]): Future[PackageInfo[NPM]] = npm.info(nameOrUrlish, maybeVersion, maybeSourceUri)
  }

}
