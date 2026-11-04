package com.jarvis.assistant.maya.connectors

import android.content.Context
import android.os.Environment
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry.ToolOutcome
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Maya connector tools: Notion, Linear, Todoist, GitLab, Google Drive, Vercel, Telegram and the
 * `github_*` extras. Registered into [MayaToolRegistry] with family "connectors".
 *
 * Tool descriptions are ported from Maya; every write/delete tool is `risky = true`.
 */
object ConnectorToolPack {

    private const val FAMILY = "connectors"

    private fun p(type: String, desc: String, enum: List<String>? = null) = MayaToolRegistry.prop(type, desc, enum)

    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
    private fun JSONObject.int(key: String, default: Int): Int = if (has(key) && !isNull(key)) optInt(key, default) else default
    private fun JSONObject.bool(key: String): Boolean = if (has(key) && !isNull(key)) (optBoolean(key) || optString(key).equals("true", true)) else false
    private fun JSONObject.need(key: String, what: String = key): String = str(key) ?: throw ConnectorException("'$what' batao — ye zaroori hai.")

    /** Wraps a handler so [ConnectorException]s become clean failures with the user message. */
    private fun tool(
        name: String,
        description: String,
        properties: JSONObject = JSONObject(),
        required: List<String> = emptyList(),
        risky: Boolean = false,
        handler: suspend (Context, JSONObject) -> ToolOutcome
    ) {
        MayaToolRegistry.register(name, description, properties, required, FAMILY, risky) { ctx, args ->
            try {
                handler(ctx, args)
            } catch (e: ConnectorException) {
                ToolOutcome.fail(e.userMessage, JSONObject().put("http_code", e.httpCode))
            }
        }
    }

    private fun resolveLocalFolder(raw: String): File {
        val f = File(raw)
        if (f.isAbsolute) return f
        val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val candidates = listOf(File(docs, raw), File(docs, "Jarvis Code/$raw"), File(Environment.getExternalStorageDirectory(), raw))
        return candidates.firstOrNull { it.exists() } ?: f
    }

    fun install(context: Context) {
        installNotion()
        installLinear()
        installTodoist()
        installGitLab()
        installDrive()
        installVercel()
        installTelegram()
        installGitHub()
    }

    // ------------------------------------------------------------------ Notion
    private fun installNotion() {
        tool(
            "notion_search",
            "Search the Notion pages the user shared with Jarvis. Returns page titles and ids. Call this first — every other Notion tool needs a page id.",
            MayaToolRegistry.props("query" to p("string", "Search text; empty lists the most recently edited pages"), "limit" to p("integer", "Max results (default 10)"))
        ) { ctx, a ->
            val pages = NotionClient(ctx).search(a.str("query").orEmpty(), a.int("limit", 10))
            if (pages.isEmpty()) return@tool ToolOutcome.ok("Kuch nahi mila. Notion me page kholo → ••• → Connections → apna integration add karo, tabhi main use dekh paungi.")
            val text = "Notion pages:\n" + pages.joinToString("\n") { "- ${it.title} (${it.kind}, edited ${it.lastEdited}) id=${it.id}" }
            ToolOutcome.ok(text, JSONObject().put("pages", JSONArray(pages.map { JSONObject().put("id", it.id).put("title", it.title).put("type", it.kind).put("url", it.url) })))
        }
        tool(
            "notion_read_page",
            "Read the text content of one Notion page.",
            MayaToolRegistry.props("page_id" to p("string", "Page id from notion_search")), listOf("page_id")
        ) { ctx, a -> ToolOutcome.ok(NotionClient(ctx).readPage(a.need("page_id"))) }
        tool(
            "notion_create_page",
            "Create a new Notion page inside an existing one.",
            MayaToolRegistry.props(
                "parent_id" to p("string", "Id of the page to create this under (from notion_search)"),
                "title" to p("string", "Title of the new page"),
                "content" to p("string", "Optional body text (paragraphs)"),
                "parent_is_database" to p("boolean", "true if parent_id is a database rather than a page")
            ), listOf("parent_id", "title"), risky = true
        ) { ctx, a ->
            val page = NotionClient(ctx).createPage(a.need("parent_id"), a.need("title"), a.str("content"), a.bool("parent_is_database"))
            ToolOutcome.ok("Notion page '${page.title}' ban gaya: ${page.url}", JSONObject().put("page_id", page.id).put("url", page.url))
        }
        tool(
            "notion_append_text",
            "Append paragraphs to the end of an existing Notion page.",
            MayaToolRegistry.props("page_id" to p("string", "Page id from notion_search"), "text" to p("string", "Text to append; newlines start new paragraphs")),
            listOf("page_id", "text"), risky = true
        ) { ctx, a ->
            val n = NotionClient(ctx).appendText(a.need("page_id"), a.need("text"))
            ToolOutcome.ok("Notion page me $n paragraph add ho gaye.")
        }
    }

    // ------------------------------------------------------------------ Linear
    private fun installLinear() {
        tool("linear_list_teams", "List the user's Linear teams and their ids. Needed before creating an issue.") { ctx, _ ->
            val teams = LinearClient(ctx).listTeams()
            if (teams.isEmpty()) ToolOutcome.ok("Linear teams nahi mili.")
            else ToolOutcome.ok("Linear teams:\n" + teams.joinToString("\n") { "- ${it.name} (${it.key}) id=${it.id}" })
        }
        tool(
            "linear_my_issues", "List the Linear issues assigned to the user that aren't done yet.",
            MayaToolRegistry.props("limit" to p("integer", "Max issues (default 30)"))
        ) { ctx, a ->
            val issues = LinearClient(ctx).myIssues(a.int("limit", 30))
            if (issues.isEmpty()) ToolOutcome.ok("Linear pe tumhare naam koi pending issue nahi hai.")
            else ToolOutcome.ok("Linear issues:\n" + issues.joinToString("\n") {
                "- ${it.identifier} ${it.title} [${it.state}]${if (it.priority > 0) " p${it.priority}" else ""}${if (it.dueDate.isNotBlank()) " due ${it.dueDate}" else ""}"
            })
        }
        tool(
            "linear_create_issue", "Create a Linear issue in a team.",
            MayaToolRegistry.props(
                "team_id" to p("string", "Team id from linear_list_teams (a team key like ENG also works)"),
                "title" to p("string", "Issue title"),
                "description" to p("string", "Optional markdown description"),
                "priority" to p("integer", "0 none, 1 urgent, 2 high, 3 medium, 4 low")
            ), listOf("team_id", "title"), risky = true
        ) { ctx, a ->
            val client = LinearClient(ctx)
            val teamId = client.resolveTeamId(a.need("team_id"))
            val issue = client.createIssue(teamId, a.need("title"), a.str("description"), a.str("priority")?.toIntOrNull())
            ToolOutcome.ok("Linear issue ${issue.identifier} ban gaya: ${issue.url}", JSONObject().put("identifier", issue.identifier).put("url", issue.url))
        }
    }

    // ------------------------------------------------------------------ Todoist
    private fun installTodoist() {
        tool(
            "todoist_list_tasks", "List the user's Todoist tasks — by default everything due today or overdue.",
            MayaToolRegistry.props("filter" to p("string", "Todoist filter, e.g. 'today | overdue', 'next 7 days', '#Work'. Defaults to today and overdue."))
        ) { ctx, a ->
            val tasks = TodoistClient(ctx).listTasks(a.str("filter"))
            val f = TodoistRequests.defaultFilter(a.str("filter"))
            if (tasks.isEmpty()) ToolOutcome.ok("Todoist ($f): koi task nahi.")
            else ToolOutcome.ok("Todoist ($f):\n" + tasks.joinToString("\n") { "- " + TodoistRequests.summarize(it) })
        }
        tool(
            "todoist_add_task", "Add a task to the user's Todoist.",
            MayaToolRegistry.props(
                "content" to p("string", "The task text"),
                "due" to p("string", "When it's due, in plain English: 'tomorrow', 'next monday 9am', 'every friday'. Todoist parses this itself."),
                "priority" to p("integer", "1 (normal) to 4 (urgent)"),
                "project_id" to p("string", "Optional Todoist project id"),
                "description" to p("string", "Optional note")
            ), listOf("content"), risky = true
        ) { ctx, a ->
            val t = TodoistClient(ctx).addTask(a.need("content"), a.str("due") ?: a.str("due_string"), a.str("priority")?.toIntOrNull(), a.str("project_id"), a.str("description"))
            ToolOutcome.ok("Todoist me add kar diya: '${t.optString("content")}'${t.optJSONObject("due")?.optString("string")?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}", JSONObject().put("task_id", t.optString("id")))
        }
        tool(
            "todoist_complete_task", "Tick a Todoist task off. Needs the task id from todoist_list_tasks.",
            MayaToolRegistry.props("task_id" to p("string", "Task id from todoist_list_tasks")), listOf("task_id"), risky = true
        ) { ctx, a ->
            TodoistClient(ctx).completeTask(a.need("task_id"))
            ToolOutcome.ok("Todoist task complete ho gaya ✅")
        }
    }

    // ------------------------------------------------------------------ GitLab
    private fun installGitLab() {
        tool("gitlab_list_projects", "List the user's GitLab projects, most recently active first.",
            MayaToolRegistry.props("limit" to p("integer", "Max projects (default 30)"))
        ) { ctx, a ->
            val projects = GitLabClient(ctx).listProjects(a.int("limit", 30))
            if (projects.isEmpty()) ToolOutcome.ok("GitLab se projects nahi mile.")
            else ToolOutcome.ok("GitLab projects:\n" + projects.joinToString("\n") { "- ${it.path} (id ${it.id}, ${it.lastActivity}) ${it.url}" })
        }
        tool("gitlab_list_issues", "List issues in a GitLab project.",
            MayaToolRegistry.props("project" to p("string", "Project path like group/name, or numeric id"), "state" to p("string", "opened (default), closed or all"), "limit" to p("integer", "Max issues (default 30)")),
            listOf("project")
        ) { ctx, a ->
            val issues = GitLabClient(ctx).listIssues(a.need("project"), a.str("state") ?: "opened", a.int("limit", 30))
            if (issues.isEmpty()) ToolOutcome.ok("Is project me koi ${a.str("state") ?: "open"} issue nahi hai.")
            else ToolOutcome.ok("GitLab issues:\n" + issues.joinToString("\n") { "- #${it.iid} ${it.title} [${it.state}]${if (it.assignee.isNotBlank()) " @${it.assignee}" else ""}" })
        }
        tool("gitlab_create_issue", "Open a new issue on a GitLab project.",
            MayaToolRegistry.props("project" to p("string", "Project path like group/name, or numeric id"), "title" to p("string", "Issue title"), "description" to p("string", "Optional description"), "labels" to p("string", "Optional comma-separated labels")),
            listOf("project", "title"), risky = true
        ) { ctx, a ->
            val issue = GitLabClient(ctx).createIssue(a.need("project"), a.need("title"), a.str("description"), a.str("labels"))
            ToolOutcome.ok("GitLab issue #${issue.iid} ban gaya: ${issue.url}")
        }
        tool("gitlab_read_file", "Read one file from a GitLab project.",
            MayaToolRegistry.props("project" to p("string", "Project path or id"), "path" to p("string", "File path inside the repo"), "ref" to p("string", "Branch; defaults to the project's default")),
            listOf("project", "path")
        ) { ctx, a ->
            val text = GitLabClient(ctx).readFile(a.need("project"), a.need("path"), a.str("ref"))
            ToolOutcome.ok(if (text.length > 12000) text.take(12000) + "\n…(truncated)" else text)
        }
        tool("gitlab_write_file", "Create or overwrite a file in a GitLab project and commit it.",
            MayaToolRegistry.props("project" to p("string", "Project path or id"), "path" to p("string", "File path inside the repo"), "content" to p("string", "Full file content"), "message" to p("string", "Commit message"), "branch" to p("string", "Branch to commit to; defaults to the project's default")),
            listOf("project", "path", "content"), risky = true
        ) { ctx, a ->
            val (branch, created) = GitLabClient(ctx).writeFile(a.need("project"), a.need("path"), a.need("content"), a.str("message"), a.str("branch"))
            ToolOutcome.ok("${if (created) "Created" else "Updated"} ${a.str("path")} on $branch (committed)")
        }
    }

    // ------------------------------------------------------------------ Google Drive
    private fun installDrive() {
        tool("drive_list_files", "List the Google Drive files Jarvis can reach — the ones it created and the ones the user picked. Call this first to get a file id.",
            MayaToolRegistry.props("query" to p("string", "Optional name filter"), "folder_id" to p("string", "Optional folder id to list inside"), "limit" to p("integer", "Max files (default 25)"))
        ) { ctx, a ->
            val files = GoogleDriveClient(ctx).listFiles(a.str("query"), a.str("folder_id"), a.int("limit", 25))
            if (files.isEmpty()) ToolOutcome.ok("Abhi meri pahunch me koi file nahi hai. Jo files main banaungi (drive_create_doc / drive_upload_file) wo yahan dikhengi.")
            else ToolOutcome.ok("Drive files:\n" + files.joinToString("\n") { "- ${it.name}${if (DriveShare.isFolder(it.mimeType)) "/" else ""} (${it.modified}) id=${it.id}" })
        }
        tool("drive_upload_file", "Upload a file from this phone to Google Drive.",
            MayaToolRegistry.props("path" to p("string", "Absolute path of the file on the phone"), "folder_id" to p("string", "Optional Drive folder id"), "name" to p("string", "Optional name in Drive")),
            listOf("path"), risky = true
        ) { ctx, a ->
            val f = GoogleDriveClient(ctx).uploadFile(a.need("path"), a.str("folder_id"), a.str("name"))
            ToolOutcome.ok("Drive pe upload ho gaya: ${f.name} — ${f.link}", JSONObject().put("file_id", f.id).put("link", f.link))
        }
        tool("drive_create_doc", "Create a new document in Google Drive from text and return its link. Use this to save notes, summaries or reports the user asked to keep.",
            MayaToolRegistry.props("title" to p("string", "Document title"), "text" to p("string", "Document body text"), "folder_id" to p("string", "Optional Drive folder id")),
            listOf("title", "text"), risky = true
        ) { ctx, a ->
            val f = GoogleDriveClient(ctx).createDoc(a.need("title"), a.need("text"), a.str("folder_id"))
            ToolOutcome.ok("Google Doc ban gaya: ${f.name} — ${f.link}", JSONObject().put("file_id", f.id).put("link", f.link))
        }
        tool("drive_create_folder", "Create a folder in Google Drive.",
            MayaToolRegistry.props("name" to p("string", "Folder name"), "parent_id" to p("string", "Optional parent folder id")), listOf("name"), risky = true
        ) { ctx, a ->
            val f = GoogleDriveClient(ctx).createFolder(a.need("name"), a.str("parent_id"))
            ToolOutcome.ok("Folder '${f.name}' ban gaya — ${f.link}", JSONObject().put("folder_id", f.id))
        }
        tool("drive_read_file", "Read a Google Drive file's text. Google Docs and Sheets are exported as text automatically.",
            MayaToolRegistry.props("file_id" to p("string", "File id from drive_list_files")), listOf("file_id")
        ) { ctx, a ->
            val (f, text) = GoogleDriveClient(ctx).readFileText(a.need("file_id"))
            ToolOutcome.ok("${f.name}:\n$text")
        }
        tool("drive_share_link",
            "Get a shareable link for a Drive file, making it viewable by ANYONE with the link. TWO STEPS: call once without `confirmed` — you get the file's name and a question to read out; only after the user clearly says yes call again with confirmed=true.",
            MayaToolRegistry.props("file_id" to p("string", "File id from drive_list_files"), "confirmed" to p("boolean", "true only after the user said yes to sharing publicly")),
            listOf("file_id"), risky = true
        ) { ctx, a ->
            val client = GoogleDriveClient(ctx)
            if (!a.bool("confirmed")) {
                val f = client.getFile(a.need("file_id"))
                return@tool ToolOutcome.ok("User se poochho: \"'${f.name}' ko link se sabke liye khol dun?\" aur saaf 'haan' ke baad drive_share_link dobara chalao, same file_id, confirmed=true.", JSONObject().put("needs_confirmation", true))
            }
            val (f, link) = client.shareAnyoneWithLink(a.need("file_id"))
            ToolOutcome.ok("'${f.name}' ab link se khula hai: $link", JSONObject().put("link", link))
        }
        tool("drive_trash_file", "Move a Drive file to the trash. Recoverable from the user's Drive for 30 days.",
            MayaToolRegistry.props("file_id" to p("string", "File id from drive_list_files")), listOf("file_id"), risky = true
        ) { ctx, a ->
            val f = GoogleDriveClient(ctx).trash(a.need("file_id"))
            ToolOutcome.ok("'${f.name}' trash me chala gaya (30 din tak Drive se wapas la sakte ho).")
        }
    }

    // ------------------------------------------------------------------ Vercel
    private fun installVercel() {
        tool("vercel_deploy",
            "Deploy a static site folder from this phone to Vercel (uses the Vercel token from Settings > Connectors) and give the live URL. The folder needs an index.html at its top level. Waits up to 30 seconds for the build.",
            MayaToolRegistry.props("folder" to p("string", "Folder path on the phone (absolute, or relative to Documents)"), "project" to p("string", "Vercel project name (lowercase, dashes); defaults to the folder name")),
            listOf("folder"), risky = true
        ) { ctx, a ->
            val client = VercelClient(ctx)
            val folder = resolveLocalFolder(a.need("folder"))
            val dep = client.deployFolder(folder, a.str("project") ?: folder.name)
            val log = if (dep.state == "ERROR") client.buildLogTail(dep.id) else ""
            val msg = client.describeState(dep, log)
            if (dep.state == "READY") ToolOutcome.ok(msg, JSONObject().put("url", dep.url).put("deployment_id", dep.id))
            else ToolOutcome.fail(msg, JSONObject().put("url", dep.url).put("deployment_id", dep.id).put("state", dep.state))
        }
        tool("vercel_list_deployments", "List your Vercel projects' latest deployments with their state and URL.",
            MayaToolRegistry.props("project" to p("string", "Optional project name to filter"), "limit" to p("integer", "Max deployments (default 10)"))
        ) { ctx, a ->
            val deps = VercelClient(ctx).listDeployments(a.int("limit", 10), a.str("project"))
            if (deps.isEmpty()) ToolOutcome.ok("Vercel pe koi deployment nahi mila.")
            else ToolOutcome.ok("Vercel deployments:\n" + deps.joinToString("\n") { "- ${it.name}: ${it.state} ${it.url} (id ${it.id})" })
        }
        tool("vercel_deployment_status", "Check the status of one Vercel deployment (READY / BUILDING / ERROR), with the build log tail if it failed.",
            MayaToolRegistry.props("deployment_id" to p("string", "Deployment id from vercel_deploy or vercel_list_deployments")), listOf("deployment_id")
        ) { ctx, a ->
            val client = VercelClient(ctx)
            val dep = client.getDeployment(a.need("deployment_id"))
            val log = if (dep.state == "ERROR") client.buildLogTail(dep.id) else ""
            ToolOutcome.ok(client.describeState(dep, log), JSONObject().put("state", dep.state).put("url", dep.url))
        }
    }

    // ------------------------------------------------------------------ Telegram
    private fun installTelegram() {
        tool("telegram_send_message",
            "Send a Telegram message to the user's own saved chat. Good for handing them a result they want to keep, or pinging them when a long job is done.",
            MayaToolRegistry.props("text" to p("string", "Message text"), "chat_id" to p("string", "Optional chat id; defaults to the detected chat")),
            listOf("text"), risky = true
        ) { ctx, a ->
            TelegramClient(ctx).sendMessage(a.need("text"), a.str("chat_id"))
            ToolOutcome.ok("Telegram pe bhej diya ✅")
        }
        tool("telegram_send_file", "Send a file from this phone to the user's Telegram chat.",
            MayaToolRegistry.props("path" to p("string", "Absolute path of the file on the phone"), "caption" to p("string", "Optional caption"), "chat_id" to p("string", "Optional chat id; defaults to the detected chat")),
            listOf("path"), risky = true
        ) { ctx, a ->
            val name = TelegramClient(ctx).sendDocument(a.need("path"), a.str("caption"), a.str("chat_id"))
            ToolOutcome.ok("Telegram pe file bhej di: $name ✅")
        }
    }

    // ------------------------------------------------------------------ GitHub extras
    private fun installGitHub() {
        val repoProp = "repo" to p("string", "Repository as owner/name (a bare name means the user's own repo)")

        tool("github_list_repos", "List the user's GitHub repositories, most recently pushed first, with the language and when each was last touched. Use this first when the user names a project loosely and you need its exact repo name.",
            MayaToolRegistry.props("limit" to p("integer", "Max repos (default 30)"))
        ) { ctx, a ->
            val repos = GitHubExtraClient(ctx).listRepos(a.int("limit", 30))
            if (repos.isEmpty()) ToolOutcome.ok("GitHub pe koi repo nahi mila.")
            else ToolOutcome.ok("GitHub repos:\n" + repos.joinToString("\n") { "- ${it.fullName}${if (it.isPrivate) " (private)" else ""} ${it.language.ifBlank { "" }} · pushed ${it.pushed}" })
        }
        tool("github_find_repo", "Find a repository of the user's by a loose name or keyword and return its exact owner/name.",
            MayaToolRegistry.props("query" to p("string", "Name or keyword")), listOf("query")
        ) { ctx, a ->
            val repos = GitHubExtraClient(ctx).findRepo(a.need("query"))
            if (repos.isEmpty()) ToolOutcome.ok("'${a.str("query")}' jaisa koi repo nahi mila — github_list_repos se poori list dekho.")
            else ToolOutcome.ok("Matches:\n" + repos.joinToString("\n") { "- ${it.fullName}${if (it.description.isNotBlank()) " — ${it.description.take(80)}" else ""} · pushed ${it.pushed}" })
        }
        tool("github_repo_overview", "Get the facts about a repository: what it says it is, its main language and size, default branch, open issue count, last push, and the top-level layout.",
            MayaToolRegistry.props(repoProp), listOf("repo")
        ) { ctx, a -> ToolOutcome.ok(GitHubExtraClient(ctx).repoOverview(a.need("repo"))) }
        tool("github_browse_tree", "List what is inside a repository folder — files with their sizes, and sub-folders. Use it to find your way around before reading files.",
            MayaToolRegistry.props(repoProp, "path" to p("string", "Folder inside the repo; omit for the top level"), "ref" to p("string", "Branch, tag or commit; defaults to the default branch")), listOf("repo")
        ) { ctx, a -> ToolOutcome.ok(GitHubExtraClient(ctx).browseTree(a.need("repo"), a.str("path"), a.str("ref"))) }
        tool("github_read_file", "Read one file from a GitHub repository. Returns the text with line numbers, so the exact lines can be quoted back to github_edit_file. For a big file read a window with from_line/max_lines rather than the whole thing.",
            MayaToolRegistry.props(repoProp, "path" to p("string", "File path inside the repo"), "ref" to p("string", "Branch, tag or commit sha; defaults to the default branch"), "from_line" to p("integer", "First line to return (default 1)"), "max_lines" to p("integer", "How many lines (default 400)")),
            listOf("repo", "path")
        ) { ctx, a -> ToolOutcome.ok(GitHubExtraClient(ctx).readFile(a.need("repo"), a.need("path"), a.str("ref"), a.int("from_line", 1), a.int("max_lines", 400))) }
        tool("github_write_file", "Create a file in a GitHub repository, or replace one completely, and commit it. The WHOLE file content must be supplied. To change a few lines inside an existing file use github_edit_file instead — it is safer and far cheaper.",
            MayaToolRegistry.props(repoProp, "path" to p("string", "File path inside the repo"), "content" to p("string", "Full new file content"), "message" to p("string", "Commit message"), "branch" to p("string", "Branch to commit to; defaults to the repo's default branch")),
            listOf("repo", "path", "content"), risky = true
        ) { ctx, a -> ToolOutcome.ok(GitHubExtraClient(ctx).writeFile(a.need("repo"), a.need("path"), a.optString("content"), a.str("message"), a.str("branch"))) }
        tool("github_edit_file", "Change a few lines inside an existing file and commit: give old_text (an exact, unique snippet copied from github_read_file) and new_text, or a from_line/to_line range to replace with new_text.",
            MayaToolRegistry.props(repoProp, "path" to p("string", "File path inside the repo"), "old_text" to p("string", "Exact text to replace (must occur once)"), "new_text" to p("string", "Replacement text"), "from_line" to p("integer", "Alternative: first line to replace"), "to_line" to p("integer", "Alternative: last line to replace (inclusive)"), "message" to p("string", "Commit message"), "branch" to p("string", "Branch to commit on; defaults to the repo's default branch")),
            listOf("repo", "path", "new_text"), risky = true
        ) { ctx, a ->
            ToolOutcome.ok(GitHubExtraClient(ctx).editFile(a.need("repo"), a.need("path"), a.str("old_text"), a.optString("new_text"), a.str("message"), a.str("branch"),
                a.str("from_line")?.toIntOrNull(), a.str("to_line")?.toIntOrNull()))
        }
        tool("github_delete_file", "Delete a file from a GitHub repository and commit the deletion. The file stays in the repo's history and can be restored.",
            MayaToolRegistry.props(repoProp, "path" to p("string", "File path inside the repo"), "message" to p("string", "Commit message"), "branch" to p("string", "Branch; defaults to the repo's default branch")),
            listOf("repo", "path"), risky = true
        ) { ctx, a -> ToolOutcome.ok(GitHubExtraClient(ctx).deleteFile(a.need("repo"), a.need("path"), a.str("message"), a.str("branch"))) }
        tool("github_create_branch", "Create a new branch in a repository, starting from an existing one.",
            MayaToolRegistry.props(repoProp, "name" to p("string", "New branch name"), "from" to p("string", "Branch to start from; defaults to the repo's default branch")),
            listOf("repo", "name"), risky = true
        ) { ctx, a -> ToolOutcome.ok(GitHubExtraClient(ctx).createBranch(a.need("repo"), a.need("name"), a.str("from"))) }
        tool("github_list_branches", "List a repository's branches, marking the default one and whether each is protected.",
            MayaToolRegistry.props(repoProp), listOf("repo")
        ) { ctx, a -> ToolOutcome.ok(GitHubExtraClient(ctx).listBranches(a.need("repo"))) }
        tool("github_compare_branches", "Compare two branches, tags or commits: how many commits apart they are and which files differ. Use before opening a pull request.",
            MayaToolRegistry.props(repoProp, "base" to p("string", "Base branch (e.g. main)"), "head" to p("string", "Head branch to compare against base")),
            listOf("repo", "base", "head")
        ) { ctx, a -> ToolOutcome.ok(GitHubExtraClient(ctx).compareBranches(a.need("repo"), a.need("base"), a.need("head"))) }
        tool("github_list_issues", "List issues in a GitHub repository. Pull requests are excluded — use github_list_prs for those.",
            MayaToolRegistry.props(repoProp, "state" to p("string", "open (default), closed or all"), "limit" to p("integer", "Max issues (default 20)")), listOf("repo")
        ) { ctx, a -> ToolOutcome.ok(GitHubExtraClient(ctx).listIssues(a.need("repo"), a.str("state") ?: "open", a.int("limit", 20))) }
        tool("github_read_issue", "Read one issue in full: its description, labels, who it is assigned to, and the discussion on it.",
            MayaToolRegistry.props(repoProp, "number" to p("integer", "Issue number")), listOf("repo", "number")
        ) { ctx, a -> ToolOutcome.ok(GitHubExtraClient(ctx).readIssue(a.need("repo"), a.need("number").toInt())) }
        tool("github_create_issue", "Open a new issue on a GitHub repository.",
            MayaToolRegistry.props(repoProp, "title" to p("string", "Issue title"), "body" to p("string", "Optional description"), "labels" to p("string", "Optional comma-separated labels")),
            listOf("repo", "title"), risky = true
        ) { ctx, a ->
            val labels = a.str("labels")?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
            ToolOutcome.ok(GitHubExtraClient(ctx).createIssue(a.need("repo"), a.need("title"), a.str("body"), labels))
        }
        tool("github_comment_issue", "Add a comment to an existing issue or pull request.",
            MayaToolRegistry.props(repoProp, "number" to p("integer", "Issue or PR number"), "body" to p("string", "Comment text")), listOf("repo", "number", "body"), risky = true
        ) { ctx, a -> ToolOutcome.ok(GitHubExtraClient(ctx).commentIssue(a.need("repo"), a.need("number").toInt(), a.need("body"))) }
        tool("github_close_issue", "Close an issue, or reopen a closed one.",
            MayaToolRegistry.props(repoProp, "number" to p("integer", "Issue number"), "reopen" to p("boolean", "true to reopen instead of close")), listOf("repo", "number"), risky = true
        ) { ctx, a -> ToolOutcome.ok(GitHubExtraClient(ctx).closeIssue(a.need("repo"), a.need("number").toInt(), a.bool("reopen"))) }
        tool("github_list_prs", "List pull requests on a repository.",
            MayaToolRegistry.props(repoProp, "state" to p("string", "open (default), closed or all"), "limit" to p("integer", "Max PRs (default 20)")), listOf("repo")
        ) { ctx, a -> ToolOutcome.ok(GitHubExtraClient(ctx).listPrs(a.need("repo"), a.str("state") ?: "open", a.int("limit", 20))) }
        tool("github_read_pr", "Read one pull request: what it says, which branch into which, how many commits and files, whether it can merge cleanly, and whether its checks passed.",
            MayaToolRegistry.props(repoProp, "number" to p("integer", "Pull request number")), listOf("repo", "number")
        ) { ctx, a -> ToolOutcome.ok(GitHubExtraClient(ctx).readPr(a.need("repo"), a.need("number").toInt())) }
        tool("github_create_pr", "Open a pull request from one branch into another.",
            MayaToolRegistry.props(repoProp, "title" to p("string", "Pull request title"), "head" to p("string", "Branch with the changes"), "base" to p("string", "Branch to merge into; defaults to the repo's default branch"), "body" to p("string", "Optional description"), "draft" to p("boolean", "Open as a draft")),
            listOf("repo", "title", "head"), risky = true
        ) { ctx, a -> ToolOutcome.ok(GitHubExtraClient(ctx).createPr(a.need("repo"), a.need("title"), a.need("head"), a.str("base"), a.str("body"), a.bool("draft"))) }
        tool("github_merge_pr",
            "Merge a pull request. ALWAYS call this first WITHOUT confirmed — it returns what is about to be merged so you can read it out and get a yes. Only then call it again with confirmed=true. A merge notifies everyone watching the repo and usually triggers a deploy, so it is never done on a single instruction.",
            MayaToolRegistry.props(repoProp, "number" to p("integer", "Pull request number"), "confirmed" to p("boolean", "true only after the user said yes"), "method" to p("string", "merge (default), squash or rebase")),
            listOf("repo", "number"), risky = true
        ) { ctx, a -> ToolOutcome.ok(GitHubExtraClient(ctx).mergePr(a.need("repo"), a.need("number").toInt(), a.bool("confirmed"), a.str("method"))) }
        tool("github_recent_commits", "Recent commits on a repository — who, when, and what the message said.",
            MayaToolRegistry.props(repoProp, "branch" to p("string", "Branch; defaults to the default branch"), "limit" to p("integer", "How many (default 15)"), "path" to p("string", "Optional: only commits touching this path")), listOf("repo")
        ) { ctx, a -> ToolOutcome.ok(GitHubExtraClient(ctx).recentCommits(a.need("repo"), a.str("branch"), a.int("limit", 15), a.str("path"))) }
        tool("github_read_commit", "Read one commit in detail: its message, and which files it changed with the actual added and removed lines.",
            MayaToolRegistry.props(repoProp, "sha" to p("string", "Commit sha, full or short. Also accepts a branch or tag name")), listOf("repo", "sha")
        ) { ctx, a -> ToolOutcome.ok(GitHubExtraClient(ctx).readCommit(a.need("repo"), a.need("sha"))) }
        tool("github_search_code", "Search code inside the user's GitHub repositories and return the matching LINES, not just file names. Use this to find where something is defined before reading it.",
            MayaToolRegistry.props("query" to p("string", "Code search query (GitHub syntax, e.g. 'fun login language:kotlin')"), "repo" to p("string", "Optional owner/name to search only that repo"), "limit" to p("integer", "Max results (default 10)")),
            listOf("query")
        ) { ctx, a -> ToolOutcome.ok(GitHubExtraClient(ctx).searchCode(a.need("query"), a.str("repo"), a.int("limit", 10))) }
        tool("github_list_releases", "List a repository's releases with their tags, dates and download counts.",
            MayaToolRegistry.props(repoProp, "limit" to p("integer", "Max releases (default 10)")), listOf("repo")
        ) { ctx, a -> ToolOutcome.ok(GitHubExtraClient(ctx).listReleases(a.need("repo"), a.int("limit", 10))) }
        tool("github_actions_status", "Show the latest GitHub Actions workflow runs of a repository and whether they passed or failed.",
            MayaToolRegistry.props(repoProp, "limit" to p("integer", "Max runs (default 8)")), listOf("repo")
        ) { ctx, a -> ToolOutcome.ok(GitHubExtraClient(ctx).actionsStatus(a.need("repo"), a.int("limit", 8))) }
        tool("github_actions_log", "Read the log of a failed GitHub Actions job — the END of the log, which is where the error is. Use after github_actions_status shows a failure, to say WHY it failed rather than just that it did.",
            MayaToolRegistry.props(repoProp, "run_id" to p("integer", "The run id from github_actions_status; omit for the latest failed run")), listOf("repo")
        ) { ctx, a -> ToolOutcome.ok(GitHubExtraClient(ctx).actionsLog(a.need("repo"), a.str("run_id")?.toLongOrNull())) }
        tool("github_create_gist", "Create a GitHub gist from text (a quick way to share a snippet, log or note by link).",
            MayaToolRegistry.props("content" to p("string", "Text of the gist"), "filename" to p("string", "File name, e.g. notes.md (default snippet.txt)"), "description" to p("string", "Optional description"), "public" to p("boolean", "true for a public gist (default false — secret)")),
            listOf("content"), risky = true
        ) { ctx, a -> ToolOutcome.ok(GitHubExtraClient(ctx).createGist(a.str("description"), a.str("filename") ?: "snippet.txt", a.need("content"), a.bool("public"))) }
        tool("github_my_work", "Everything on GitHub waiting on the user: issues assigned to them, pull requests where their review was requested, and their own open PRs.") { ctx, _ ->
            ToolOutcome.ok(GitHubExtraClient(ctx).myWork())
        }
        tool("github_share_repo",
            "Put a project folder from this phone on the user's GitHub — creates the repository if it doesn't exist and commits every file to the main branch, so it can be deployed from Vercel/Netlify. Needs the GitHub connector linked. Tell the user the repo URL.",
            MayaToolRegistry.props("folder" to p("string", "Folder path on the phone (absolute, or relative to Documents)"), "repo_name" to p("string", "Repository name; defaults to the folder name"), "private" to p("boolean", "true for a private repo (default false — a public repo is what Vercel deploys most easily)"), "description" to p("string", "Optional repo description")),
            listOf("folder"), risky = true
        ) { ctx, a ->
            val folder = resolveLocalFolder(a.need("folder"))
            ToolOutcome.ok(GitHubExtraClient(ctx).shareRepo(folder, a.str("repo_name") ?: folder.name, a.bool("private"), a.str("description")))
        }
    }
}
