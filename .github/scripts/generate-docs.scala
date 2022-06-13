//> using scala "3.1.2"
//> using lib "com.lihaoyi::os-lib:0.8.1"
//> using lib "io.circe::circe-yaml:0.14.1"
//> using lib "com.47deg::github4s:0.31.0"
//> using lib "org.http4s::http4s-jdk-http-client:0.7.0"

import cats.effect.IO
import org.http4s.jdkhttpclient.JdkHttpClient
import github4s.{GHResponse, Github}
import github4s.domain.{Issue, PRFilterAll, PRFilterBase, Pagination}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.yaml.syntax.AsYaml

import scala.annotation.tailrec
import scala.sys.process.Process

val gitToken = sys.env("IMPROVEMENT_BOT_TOKEN")

/**
 * Generate the sources of the page https://docs.scala-lang.org/sips/all.html from the
 * proposals of the scala/improvement-proposals repository.
 * Depending on their status, the proposals can have various forms:
 *   - “under review” proposals are open PRs
 *   - “rejected” proposals are closed PRs
 *   - “withdrawn” proposals are closed PRs (with a specific label)
 *   - “waiting for implementation” proposals are Markdown files in the directory `content/`
 *   - etc.
 * We convert every proposal into a `.md` file, and we push them to the scala/docs.scala-lang repo.
 */
@main def generateDocs(): Unit =
  JdkHttpClient.simple[IO].use { httpClient =>
    val github = Github[IO](httpClient, None)
    IO {
      val sipsRepo = clone("scala/improvement-proposals", "main")
      val docsRepo = clone("scala/docs.scala-lang", "main")
      Updater(sipsRepo, docsRepo, github).update()
    }
  }.unsafeRunSync()

def clone(repo: String, branch: String): os.Path =
  println(s"Cloning ${repo}")
  val url = s"https://x-access-token:${gitToken}@github.com/${repo}"
  val path = os.temp.dir()
  run(s"git clone --branch ${branch} ${url} ${path}", os.pwd)
  run(s"git config user.name \"Scala Improvement Bot\"", path)
  run(s"git config user.email scala.improvement@epfl.ch", path)
  path

// Invoke command and make sure it succeeds. For some reason, os.proc(cmd).call(cwd) does not work.
def run(cmd: String, cwd: os.Path): Unit =
  Process(cmd, cwd = cwd.toIO).run().exitValue().ensuring(_ == 0)

/**
 * @param sipsRepo Path of the local clone of the repository scala/improvement-proposals
 * @param docsRepo Path of the local clone of the repository scala/docs.scala-lang
 * @param github   Github client
 */
class Updater(sipsRepo: os.Path, docsRepo: os.Path, github: Github[IO]):

  val outputPath = docsRepo / "_sips" / "sips"

  def update(): Unit =
    clean()
    updateMergedProposals()
    updateProposalPullRequests()
    pushChanges()

  // Remove current content in the output directory
  private def clean(): Unit =
    os.remove.all(outputPath)
    os.makeDir.all(outputPath)

  // Merged proposals are `.md` files in the `content/` directory of the improvement-proposals repository
  private def updateMergedProposals(): Unit =
    println("Updating merged SIPs")
    for sip <- os.walk(sipsRepo / "content").filter(_.ext == "md")
    do os.copy.over(sip, outputPath / sip.last)

  // Other proposals are open or closed PRs in the improvement-proposals repository
  private def updateProposalPullRequests(): Unit =
    println("Updating unmerged pull request SIPs")
    for
      pr    <- fetchUnmergedPullRequests()
      state <- decodePullRequest(pr)
    do
      // Create an empty .md file with just a YAML frontmatter describing the proposal status
      val frontmatter =
        Json.obj((
          Seq(
            "title"               -> Json.fromString(pr.title),
            "status"              -> Json.fromString(Status.label(state.status)),
            "pull-request-number" -> Json.fromInt(pr.number)
          ) ++
          state.maybeStage.map(stage => "stage" -> Json.fromString(Stage.label(stage))).toList ++
          state.maybeRecommendation.map(recommendation => "recommendation" -> Json.fromString(Recommendation.label(recommendation))).toList
        )*)
      val titleWithoutSipPrefix =
        if pr.title.startsWith("SIP-") then pr.title.drop("SIP-XX - ".length)
        else pr.title
      val fileName =
        titleWithoutSipPrefix
          .replace(' ', '-')
          .filter(char => char.isLetterOrDigit || char == '-')
          .toLowerCase
      val fileContent =
        s"""---
          |${frontmatter.asYaml.spaces2}
          |---
          |""".stripMargin
      os.write.over(outputPath / s"${fileName}.md", fileContent)

  private def pushChanges(): Unit =
    run("git add _sips/sips", docsRepo)
    if Process("git diff --cached --quiet", cwd = docsRepo.toIO).run().exitValue != 0 then
      run(s"git commit -m \"Update SIPs state\"", docsRepo)
      // Note that here the push may fail if someone pushed something in the middle
      // of the execution of the script
      run("git push", docsRepo)
    else
      println("No changes to push.")
    end if

  // Fetch all the unmerged PRs and get their corresponding “issue” (which contains more information like the labels)
  private def fetchUnmergedPullRequests(): List[Issue] =
    val prs =
      fetchAllPages { pagination =>
        github.pullRequests
          .listPullRequests(
            owner = "scala",
            repo = "improvement-proposals",
            filters = List(
              PRFilterAll,
              PRFilterBase("main")
            ),
            pagination = Some(pagination)
          )
      }
    for
      pr <- prs
      if pr.merged_at.isEmpty // Keep only unmerged PRs (merged PRs are handled by `updateMergedProposals`)
    yield
      github.issues
        .getIssue("scala", "improvement-proposals", pr.number)
        .unsafeRunSync().result.toOption.get

  private def decodePullRequest(pr: Issue): Option[State] =
    val maybeState =
      State.validStates.find { state =>
        val labels =
          List(
            state.maybeStage.map(stage => s"stage:${Stage.label(stage)}"),
            Some(s"status:${Status.label(state.status)}"),
            state.maybeRecommendation.map(recommendation => s"recommendation:${Recommendation.label(recommendation)}")
          ).flatten
        labels.forall(label => pr.labels.exists(_.name == label))
      }
    if maybeState.isEmpty then
      println(s"Ignoring pull request #${pr.number}. Unable to decode its state.")
    end if
    maybeState

  private def fetchAllPages[A](getPage: Pagination => IO[GHResponse[List[A]]]): List[A] =
    @tailrec
    def loop(pagination: Pagination, previousResults: List[A]): List[A] =
      val ghResponse = getPage(pagination).unsafeRunSync()
      val results = previousResults ++ ghResponse.result.toOption.get
      val hasNextPage =
        ghResponse.headers
          .get("Link")
          .exists { links =>
            links.split(", ")
              .exists(_.endsWith("rel=\"next\""))
          }
      if hasNextPage then loop(pagination.copy(page = pagination.page + 1), results)
      else results

    loop(Pagination(page = 1, per_page = 100), Nil)
  end fetchAllPages

end Updater

enum Stage:
  case PreSip, Design, Implementation, Completed

object Stage:
  def label(stage: Stage): String =
    stage match
      case Stage.PreSip         => "pre-sip"
      case Stage.Design         => "design"
      case Stage.Implementation => "implementation"
      case Stage.Completed      => "completed"
end Stage

enum Status:
  case Submitted, UnderReview, VoteRequested, WaitingForImplementation, Accepted, Shipped, Rejected, Withdrawn

object Status:
  def label(status: Status): String =
    status match
      case Status.Submitted                => "submitted"
      case Status.UnderReview              => "under-review"
      case Status.VoteRequested            => "vote-requested"
      case Status.WaitingForImplementation => "waiting-for-implementation"
      case Status.Accepted                 => "accepted"
      case Status.Shipped                  => "shipped"
      case Status.Rejected                 => "rejected"
      case Status.Withdrawn                => "withdrawn"
end Status

enum Recommendation:
  case Accept, Reject

object Recommendation:
  def label(recommendation: Recommendation): String =
    recommendation match
      case Recommendation.Accept => "accept"
      case Recommendation.Reject => "reject"
end Recommendation

case class State(maybeStage: Option[Stage], status: Status, maybeRecommendation: Option[Recommendation]):
  assert(maybeStage.nonEmpty || status == Status.Rejected || status == Status.Withdrawn)
  assert(status != Status.VoteRequested || maybeRecommendation.nonEmpty)

object State:

  val validStates: List[State] = List(
    State(Some(Stage.PreSip),         Status.Submitted,                None),
    State(Some(Stage.Design),         Status.UnderReview,              None),
    State(Some(Stage.Design),         Status.VoteRequested,            Some(Recommendation.Accept)),
    State(Some(Stage.Design),         Status.VoteRequested,            Some(Recommendation.Reject)),
    State(Some(Stage.Implementation), Status.WaitingForImplementation, None),
    State(Some(Stage.Implementation), Status.UnderReview,              None),
    State(Some(Stage.Implementation), Status.VoteRequested,            Some(Recommendation.Accept)),
    State(Some(Stage.Implementation), Status.VoteRequested,            Some(Recommendation.Reject)),
    State(Some(Stage.Completed),      Status.Accepted,                 None),
    State(Some(Stage.Completed),      Status.Shipped,                  None),
    State(None,                       Status.Rejected,                 None),
    State(None,                       Status.Withdrawn,                None)
  )

end State
