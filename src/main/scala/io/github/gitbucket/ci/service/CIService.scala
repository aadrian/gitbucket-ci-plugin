package io.github.gitbucket.ci.service

import gitbucket.core.model.{Account, Role}
import io.github.gitbucket.ci.manager.BuildManager
import io.github.gitbucket.ci.model._
import io.github.gitbucket.ci.model.Profile._
import gitbucket.core.model.Profile.profile.blockingApi._
import gitbucket.core.service.{AccountService, RepositoryService}
import gitbucket.core.util.Directory
import org.apache.commons.io.FileUtils

import scala.sys.process._

case class BuildJob(
  userName: String,
  repositoryName: String,
  buildNumber: Int,
  sha: String, startTime:
  Option[java.util.Date],
  config: CIConfig
)

trait SimpleCIService { self: AccountService with RepositoryService =>

  def saveCIConfig(userName: String, repositoryName: String, config: Option[CIConfig])(implicit s: Session): Unit = {
    CIConfigs.filter { t =>
      (t.userName === userName.bind) && (t.repositoryName === repositoryName.bind)
    }.delete

    config.foreach { config => CIConfigs += config }
  }

  def loadCIConfig(userName: String, repositoryName: String)(implicit s: Session): Option[CIConfig] = {
    CIConfigs.filter { t =>
      (t.userName === userName.bind) && (t.repositoryName === repositoryName.bind)
    }.firstOption
  }

  def getCIResults(userName: String, repositoryName: String)(implicit s: Session): Seq[CIResult] = {
    CIResults.filter { t =>
      (t.userName === userName.bind) && (t.repositoryName === repositoryName.bind)
    }.list
  }

  def getCIResult(userName: String, repositoryName: String, buildNumber: Int)(implicit s: Session): Option[CIResult] = {
    CIResults.filter { t =>
      (t.userName === userName.bind) && (t.repositoryName === repositoryName.bind) && (t.buildNumber === buildNumber.bind)
    }.firstOption
  }

  def runBuild(userName: String, repositoryName: String, sha: String, config: CIConfig)(implicit s: Session): Unit = {
    val buildNumber = (getCIResults(userName, repositoryName).map(_.buildNumber) match {
      case Nil => 0
      case seq => seq.max
    }) + 1

    BuildManager.queueBuildJob(BuildJob(userName, repositoryName, buildNumber, sha, None, config))
  }

  def killBuild(userName: String, repositoryName: String, buildNumber: Int): Unit = {
    BuildManager.threads.find { thread =>
      thread.runningJob.get.exists { job =>
        job.userName == userName && job.repositoryName == repositoryName && job.buildNumber == buildNumber
      }
    }.foreach { thread =>
      thread.kill()
    }
  }

  def getRunningJobs(userName: String, repositoryName: String): Seq[(BuildJob, StringBuffer)] = {
    BuildManager.threads
      .map { thread => (thread, thread.runningJob.get) }
      .collect { case (thread, Some(job)) if(job.userName == userName && job.repositoryName == repositoryName) =>
        (job, thread.sb)
      }
  }

  def getQueuedJobs(userName: String, repositoryName: String): Seq[BuildJob] = {
    import scala.collection.JavaConverters._
    BuildManager.queue.iterator.asScala.filter { job =>
      job.userName == userName && job.repositoryName == repositoryName
    }.toSeq
  }

  def saveCIResult(result: CIResult, output: String)(implicit s: Session): Unit = {
    // Delete older results
    val results = getCIResults(result.userName, result.repositoryName).sortBy(_.buildNumber)
    if (results.length >= BuildManager.MaxBuildsPerProject){
      results.take(results.length - BuildManager.MaxBuildsPerProject + 1).foreach { result =>
        // Delete from database
        CIResults.filter { t =>
          (t.userName       === result.userName.bind) &&
          (t.repositoryName === result.repositoryName.bind) &&
          (t.buildNumber    === result.buildNumber.bind)
        }.delete

        // Delete files
        val buildDir = getBuildResultDir(result)
        if(buildDir.exists){
          FileUtils.deleteQuietly(buildDir)
        }
      }
    }

    // Insert new result
    CIResults += result

    // Save result output as file
    val buildDir = getBuildResultDir(result)
    if(!buildDir.exists){
      buildDir.mkdirs()
    }
    FileUtils.write(new java.io.File(buildDir, "output"), output, "UTF-8")
  }

  def getCIResultOutput(result: CIResult): String = {
    val buildDir = getBuildResultDir(result)
    val file = new java.io.File(buildDir, "output")
    if(file.exists){
      FileUtils.readFileToString(file, "UTF-8")
    } else ""
  }

  // TODO This method should be moved to RepositoryService.
  def hasOwnerRole(owner: String, repository: String, loginAccount: Option[Account])(implicit s: Session): Boolean = {
    loginAccount match {
      case Some(a) if(a.isAdmin) => true
      case Some(a) if(a.userName == owner) => true
      case Some(a) if(getGroupMembers(owner).exists(_.userName == a.userName)) => true
      case Some(a) if(getCollaboratorUserNames(owner, repository, Seq(Role.ADMIN)).contains(a.userName)) => true
      case _ => false
    }
  }

  private def getBuildResultDir(result: CIResult): java.io.File = {
    val dir = Directory.getRepositoryDir(result.userName, result.repositoryName)
    new java.io.File(dir, s"build/${result.buildNumber}")
  }
}

class BuildProcessLogger(sb: StringBuffer) extends ProcessLogger {

  override def err(s: => String): Unit = {
    sb.append(s + "\n")
    println(s)
  }

  override def out(s: => String): Unit = {
    sb.append(s + "\n")
    println(s)
  }

  override def buffer[T](f: => T): T = ???

}