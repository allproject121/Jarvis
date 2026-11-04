package com.jarvis.assistant.maya.connectors

import android.content.Context
import android.util.Base64
import com.jarvis.assistant.connectors.GitHubConnector
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * GitHub REST (api.github.com) client for the Maya `github_*` extras. Reuses the personal token
 * stored by the existing [GitHubConnector]; nothing here clones or edits on-device repos.
 */
class GitHubExtraClient(private val context: Context) {

    companion object {
        private const val BASE = "https://api.github.com"
        private const val SERVICE = "GitHub"
        private const val MAX_FILE_BYTES = 1L * 1024 * 1024
        private val SKIP_DIRS = setOf("node_modules", ".git", ".gradle", "build", ".idea", "__pycache__")
        @Volatile private var cachedLogin: String? = null
    }

    data class Repo(val fullName: String, val defaultBranch: String, val isPrivate: Boolean, val language: String, val pushed: String, val description: String, val url: String)

    private val connector = GitHubConnector(context)

    private fun headers(accept: String = "application/vnd.github+json"): Map<String, String> {
        val token = connector.token?.takeIf { it.isNotBlank() } ?: throw ConnectorHttp.notLinked(ConnectorService.GITHUB)
        return mapOf("Authorization" to "Bearer $token", "Accept" to accept, "X-GitHub-Api-Version" to "2022-11-28")
    }

    private fun check(resp: ConnectorHttp.Resp, notFound: String? = null): ConnectorHttp.Resp {
        if (resp.ok) return resp
        val msg = when (resp.code) {
            401 -> "GitHub token galat ya expire — Settings > Connectors me naya paste karo"
            403 -> if (resp.body.contains("rate limit", true)) "GitHub ne rate limit laga di — thodi der me phir try karo"
                   else "GitHub ne mana kar diya (token ko is repo/scope ki permission nahi): ${ConnectorHttp.errorSnippet(resp.body)}"
            404 -> "GitHub pe ye ${notFound ?: "cheez"} nahi mili — naam check karo, ya token ko is repo ki permission nahi hai"
            409 -> "GitHub: conflict — repo khaali hai ya branch badal gayi; dobara try karo"
            422 -> "GitHub ne request reject kar di: ${ConnectorHttp.errorSnippet(resp.body)}"
            else -> ConnectorHttp.mapError(SERVICE, resp.code, resp.body, notFound)
        }
        throw ConnectorException(msg, resp.code)
    }

    private fun safePath(path: String): String {
        val p = path.trim().trimStart('/')
        if (p.isBlank() || p.contains("..") || p.contains('#') || p.contains('?') || p.contains('\\')) {
            throw ConnectorException("'$path' me aisa hissa hai ('..' ya '#' jaisa) jo request ko chupke se kisi doosre GitHub endpoint pe bhej deta hai. Sahi file path / repo naam do.")
        }
        return p.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }

    suspend fun login(): String {
        cachedLogin?.let { return it }
        val j = check(ConnectorHttp.get(SERVICE, "$BASE/user", headers())).json()
        val l = j.optString("login")
        if (l.isNotBlank()) { cachedLogin = l; connector.username = l }
        return l
    }

    suspend fun testConnection(): String = "GitHub linked as ${login()}"

    /** Accepts "owner/name", a github.com URL, or a bare name (→ the token owner's repo). */
    suspend fun resolveRepo(raw: String): String {
        var s = raw.trim().removeSuffix(".git").removeSuffix("/")
        if (s.contains("github.com/")) s = s.substringAfter("github.com/")
        s = s.trimStart('/')
        if (s.isBlank()) throw ConnectorException("Repo ka naam batao (owner/name).")
        if (s.contains('/')) return s.split('/').take(2).joinToString("/")
        return "${login()}/$s"
    }

    private fun parseRepo(r: JSONObject) = Repo(
        r.optString("full_name"), r.optString("default_branch", "main"), r.optBoolean("private"),
        r.optString("language").takeIf { it != "null" }.orEmpty(), r.optString("pushed_at").take(10),
        r.optString("description").takeIf { it != "null" }.orEmpty(), r.optString("html_url")
    )

    suspend fun listRepos(limit: Int = 30): List<Repo> {
        val arr = check(ConnectorHttp.get(SERVICE, "$BASE/user/repos?sort=pushed&per_page=${limit.coerceIn(1, 100)}", headers())).jsonArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { parseRepo(it) }
    }

    suspend fun findRepo(query: String, limit: Int = 10): List<Repo> {
        val q = URLEncoder.encode("$query user:${login()}", "UTF-8")
        val arr = check(ConnectorHttp.get(SERVICE, "$BASE/search/repositories?q=$q&per_page=$limit", headers())).json().optJSONArray("items") ?: JSONArray()
        val found = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { parseRepo(it) }
        if (found.isNotEmpty()) return found
        // Fallback: fuzzy match over the user's own repos.
        val needle = query.lowercase().replace(Regex("[^a-z0-9]"), "")
        return listRepos(100).filter { it.fullName.substringAfter('/').lowercase().replace(Regex("[^a-z0-9]"), "").contains(needle) }.take(limit)
    }

    suspend fun repoOverview(repo: String): String {
        val full = resolveRepo(repo)
        val r = check(ConnectorHttp.get(SERVICE, "$BASE/repos/$full", headers()), "repo '$full'").json()
        val tree = check(ConnectorHttp.get(SERVICE, "$BASE/repos/$full/contents?ref=${r.optString("default_branch", "main")}", headers())).jsonArray()
        val entries = (0 until tree.length()).mapNotNull { tree.optJSONObject(it) }
            .map { (if (it.optString("type") == "dir") it.optString("name") + "/" else it.optString("name")) }
        return buildString {
            appendLine("${r.optString("full_name")} (${if (r.optBoolean("private")) "private" else "public"})")
            r.optString("description").takeIf { it.isNotBlank() && it != "null" }?.let { appendLine(it) }
            appendLine("Language: ${r.optString("language").takeIf { it != "null" } ?: "—"} | Size: ${r.optLong("size") / 1024} MB | Default branch: ${r.optString("default_branch")}")
            appendLine("Stars: ${r.optInt("stargazers_count")} | Forks: ${r.optInt("forks_count")} | Open issues: ${r.optInt("open_issues_count")} | Last push: ${r.optString("pushed_at").take(10)}")
            appendLine("Top level: ${entries.joinToString(", ")}")
            append(r.optString("html_url"))
        }
    }

    suspend fun browseTree(repo: String, path: String?, ref: String?): String {
        val full = resolveRepo(repo)
        val p = path?.trim()?.trimStart('/')?.trimEnd('/').orEmpty()
        val url = "$BASE/repos/$full/contents/${if (p.isBlank()) "" else safePath(p)}" + (ref?.takeIf { it.isNotBlank() }?.let { "?ref=${URLEncoder.encode(it, "UTF-8")}" } ?: "")
        val resp = check(ConnectorHttp.get(SERVICE, url, headers()), "folder '${p.ifBlank { "/" }}'")
        val arr = try { JSONArray(resp.body) } catch (_: Exception) { return "'$p' ek file hai, folder nahi — github_read_file use karo." }
        val sb = StringBuilder("$full/${p.ifBlank { "" }} (${arr.length()} items)\n")
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            if (e.optString("type") == "dir") sb.appendLine("  ${e.optString("name")}/")
            else sb.appendLine("  ${e.optString("name")}  (${e.optLong("size")} B)")
        }
        return sb.toString().trimEnd()
    }

    private suspend fun getContent(full: String, path: String, ref: String?): JSONObject {
        val url = "$BASE/repos/$full/contents/${safePath(path)}" + (ref?.takeIf { it.isNotBlank() }?.let { "?ref=${URLEncoder.encode(it, "UTF-8")}" } ?: "")
        return check(ConnectorHttp.get(SERVICE, url, headers()), "file '$path'").json()
    }

    private fun decode(j: JSONObject): String {
        val enc = j.optString("encoding")
        val content = j.optString("content")
        return if (enc == "base64") String(Base64.decode(content.replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8) else content
    }

    suspend fun readFile(repo: String, path: String, ref: String?, fromLine: Int = 1, maxLines: Int = 400): String {
        val full = resolveRepo(repo)
        val j = getContent(full, path, ref)
        if (j.optString("type") == "dir" || j.has("entries")) return "'$path' ek folder hai — github_browse_tree use karo."
        if (j.optLong("size") > MAX_FILE_BYTES) throw ConnectorException("'$path' 1 MB se badi hai — itni badi file yahan nahi padh sakti.")
        val lines = decode(j).split("\n")
        val start = fromLine.coerceAtLeast(1)
        val end = minOf(lines.size, start - 1 + maxLines.coerceIn(1, 2000))
        val sb = StringBuilder("$full:$path (lines $start-$end of ${lines.size})\n")
        for (i in (start - 1) until end) sb.append(i + 1).append(": ").append(lines[i]).append('\n')
        return sb.toString().trimEnd()
    }

    private suspend fun defaultBranch(full: String): String =
        check(ConnectorHttp.get(SERVICE, "$BASE/repos/$full", headers()), "repo '$full'").json().optString("default_branch", "main")

    /** Create or fully replace a file (commit). Refuses to overwrite with empty content. */
    suspend fun writeFile(repo: String, path: String, content: String, message: String?, branch: String?): String {
        val full = resolveRepo(repo)
        val br = branch?.takeIf { it.isNotBlank() } ?: defaultBranch(full)
        val existing = try { getContent(full, path, br) } catch (e: ConnectorException) { if (e.httpCode == 404) null else throw e }
        if (existing != null && content.isEmpty()) {
            throw ConnectorException("'$path' pehle se hai aur call me 'content' khaali aaya — isko khaali karke overwrite nahi karungi. Poora naya content bhejo, ya thoda badalna ho to github_edit_file.")
        }
        val body = JSONObject().apply {
            put("message", message?.takeIf { it.isNotBlank() } ?: (if (existing != null) "Update $path" else "Add $path"))
            put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            put("branch", br)
            existing?.optString("sha")?.takeIf { it.isNotBlank() }?.let { put("sha", it) }
        }
        val j = check(ConnectorHttp.putJson(SERVICE, "$BASE/repos/$full/contents/${safePath(path)}", body, headers())).json()
        val sha = j.optJSONObject("commit")?.optString("sha").orEmpty().take(7)
        return "${if (existing != null) "Updated" else "Created"} $path on $full@$br (commit $sha)"
    }

    /** Replace one exact text occurrence (or a line range) inside a file and commit. */
    suspend fun editFile(repo: String, path: String, oldText: String?, newText: String, message: String?, branch: String?, fromLine: Int?, toLine: Int?): String {
        val full = resolveRepo(repo)
        val br = branch?.takeIf { it.isNotBlank() } ?: defaultBranch(full)
        val existing = try { getContent(full, path, br) } catch (e: ConnectorException) {
            if (e.httpCode == 404) throw ConnectorException("'$path' abhi hai hi nahi — edit kis cheez me karun? Nayi file banani hai to github_write_file use karo.") else throw e
        }
        val current = decode(existing)
        val updated: String = if (!oldText.isNullOrEmpty()) {
            var count = 0
            var idx = current.indexOf(oldText)
            while (idx >= 0 && count < 3) { count++; idx = current.indexOf(oldText, idx + oldText.length) }
            when {
                count == 0 -> throw ConnectorException("Jo text badalna tha wo '$path' me mila hi nahi. Kaam thoda alag tarike se batao, ya file khud padh ke github_edit_file se karo.")
                count > 1 -> throw ConnectorException("Wo text '$path' me $count jagah hai — thoda aur context do taaki ek hi jagah match ho.")
                else -> current.replaceFirst(oldText, newText)
            }
        } else if (fromLine != null && fromLine >= 1) {
            val lines = current.split("\n").toMutableList()
            val to = (toLine ?: fromLine).coerceIn(fromLine, lines.size)
            if (fromLine > lines.size) throw ConnectorException("File me sirf ${lines.size} lines hain.")
            val head = lines.subList(0, fromLine - 1)
            val tail = lines.subList(to, lines.size)
            (head + newText.split("\n") + tail).joinToString("\n")
        } else throw ConnectorException("Batao kya badalna hai: old_text (exact) ya from_line/to_line.")
        if (updated == current) return "Koi badlaav nahi hua — file pehle se waisi hi hai."
        val body = JSONObject().apply {
            put("message", message?.takeIf { it.isNotBlank() } ?: "Edit $path")
            put("content", Base64.encodeToString(updated.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            put("branch", br)
            put("sha", existing.optString("sha"))
        }
        val j = check(ConnectorHttp.putJson(SERVICE, "$BASE/repos/$full/contents/${safePath(path)}", body, headers())).json()
        return "Edited $path on $full@$br (commit ${j.optJSONObject("commit")?.optString("sha").orEmpty().take(7)})"
    }

    suspend fun deleteFile(repo: String, path: String, message: String?, branch: String?): String {
        val full = resolveRepo(repo)
        val br = branch?.takeIf { it.isNotBlank() } ?: defaultBranch(full)
        val existing = getContent(full, path, br)
        val body = JSONObject().put("message", message?.takeIf { it.isNotBlank() } ?: "Delete $path").put("sha", existing.optString("sha")).put("branch", br)
        check(ConnectorHttp.deleteJson(SERVICE, "$BASE/repos/$full/contents/${safePath(path)}", body, headers()))
        return "Deleted $path from $full@$br (recoverable from history)"
    }

    suspend fun listBranches(repo: String): String {
        val full = resolveRepo(repo)
        val def = defaultBranch(full)
        val arr = check(ConnectorHttp.get(SERVICE, "$BASE/repos/$full/branches?per_page=100", headers())).jsonArray()
        val sb = StringBuilder("$full branches (${arr.length()}):\n")
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            val n = b.optString("name")
            sb.appendLine("  $n${if (n == def) " (default)" else ""}${if (b.optBoolean("protected")) " [protected]" else ""}")
        }
        return sb.toString().trimEnd()
    }

    suspend fun createBranch(repo: String, name: String, from: String?): String {
        val full = resolveRepo(repo)
        val base = from?.takeIf { it.isNotBlank() } ?: defaultBranch(full)
        val ref = check(ConnectorHttp.get(SERVICE, "$BASE/repos/$full/git/ref/heads/${URLEncoder.encode(base, "UTF-8")}", headers()), "branch '$base'").json()
        val sha = ref.optJSONObject("object")?.optString("sha") ?: throw ConnectorException("Branch '$base' ka sha nahi mila")
        val body = JSONObject().put("ref", "refs/heads/${name.trim()}").put("sha", sha)
        check(ConnectorHttp.postJson(SERVICE, "$BASE/repos/$full/git/refs", body, headers()))
        return "Branch '${name.trim()}' created from $base on $full"
    }

    suspend fun compareBranches(repo: String, base: String, head: String): String {
        val full = resolveRepo(repo)
        val j = check(ConnectorHttp.get(SERVICE, "$BASE/repos/$full/compare/${URLEncoder.encode(base, "UTF-8")}...${URLEncoder.encode(head, "UTF-8")}", headers()), "branch").json()
        val files = j.optJSONArray("files") ?: JSONArray()
        val sb = StringBuilder("$full: $head is ${j.optInt("ahead_by")} ahead, ${j.optInt("behind_by")} behind $base (${j.optString("status")}); ${j.optInt("total_commits")} commits, ${files.length()} files differ\n")
        for (i in 0 until minOf(files.length(), 40)) {
            val f = files.optJSONObject(i) ?: continue
            sb.appendLine("  ${f.optString("status")} ${f.optString("filename")} (+${f.optInt("additions")}/-${f.optInt("deletions")})")
        }
        return sb.toString().trimEnd()
    }

    suspend fun listIssues(repo: String, state: String = "open", limit: Int = 20): String {
        val full = resolveRepo(repo)
        val arr = check(ConnectorHttp.get(SERVICE, "$BASE/repos/$full/issues?state=$state&per_page=${(limit * 2).coerceIn(1, 100)}", headers()), "repo '$full'").jsonArray()
        val sb = StringBuilder()
        var n = 0
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            if (it.has("pull_request")) continue
            val labels = it.optJSONArray("labels")?.let { l -> (0 until l.length()).mapNotNull { l.optJSONObject(it)?.optString("name") } }.orEmpty()
            sb.appendLine("#${it.optInt("number")} ${it.optString("title")}${if (labels.isNotEmpty()) " [${labels.joinToString(", ")}]" else ""} — ${it.optJSONObject("user")?.optString("login")} · ${it.optString("updated_at").take(10)}")
            if (++n >= limit) break
        }
        return if (n == 0) "$full pe koi $state issue nahi hai." else "$full $state issues:\n$sb".trimEnd()
    }

    suspend fun readIssue(repo: String, number: Int): String {
        val full = resolveRepo(repo)
        val it = check(ConnectorHttp.get(SERVICE, "$BASE/repos/$full/issues/$number", headers()), "issue #$number").json()
        val comments = check(ConnectorHttp.get(SERVICE, "$BASE/repos/$full/issues/$number/comments?per_page=20", headers())).jsonArray()
        val labels = it.optJSONArray("labels")?.let { l -> (0 until l.length()).mapNotNull { l.optJSONObject(it)?.optString("name") } }.orEmpty()
        val assignees = it.optJSONArray("assignees")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("login") } }.orEmpty()
        val sb = StringBuilder()
        sb.appendLine("#${it.optInt("number")} ${it.optString("title")} (${it.optString("state")}) by ${it.optJSONObject("user")?.optString("login")} · ${it.optString("created_at").take(10)}")
        if (labels.isNotEmpty()) sb.appendLine("Labels: ${labels.joinToString(", ")}")
        if (assignees.isNotEmpty()) sb.appendLine("Assigned: ${assignees.joinToString(", ")}")
        sb.appendLine(it.optString("body").takeIf { it != "null" }.orEmpty().take(4000))
        if (comments.length() > 0) {
            sb.appendLine("\nComments (${comments.length()}):")
            for (i in 0 until comments.length()) {
                val c = comments.optJSONObject(i) ?: continue
                sb.appendLine("- ${c.optJSONObject("user")?.optString("login")} (${c.optString("created_at").take(10)}): ${c.optString("body").take(600)}")
            }
        }
        return sb.toString().trimEnd()
    }

    suspend fun createIssue(repo: String, title: String, body: String?, labels: List<String>): String {
        val full = resolveRepo(repo)
        val payload = JSONObject().put("title", title.trim())
        if (!body.isNullOrBlank()) payload.put("body", body)
        if (labels.isNotEmpty()) payload.put("labels", JSONArray(labels))
        val j = check(ConnectorHttp.postJson(SERVICE, "$BASE/repos/$full/issues", payload, headers()), "repo '$full'").json()
        return "Issue #${j.optInt("number")} opened on $full: ${j.optString("html_url")}"
    }

    suspend fun commentIssue(repo: String, number: Int, body: String): String {
        val full = resolveRepo(repo)
        val j = check(ConnectorHttp.postJson(SERVICE, "$BASE/repos/$full/issues/$number/comments", JSONObject().put("body", body), headers()), "issue #$number").json()
        return "Comment added on $full#$number: ${j.optString("html_url")}"
    }

    suspend fun closeIssue(repo: String, number: Int, reopen: Boolean): String {
        val full = resolveRepo(repo)
        val payload = JSONObject().put("state", if (reopen) "open" else "closed")
        if (!reopen) payload.put("state_reason", "completed")
        check(ConnectorHttp.patchJson(SERVICE, "$BASE/repos/$full/issues/$number", payload, headers()), "issue #$number")
        return "Issue $full#$number ${if (reopen) "reopened" else "closed"}"
    }

    suspend fun listPrs(repo: String, state: String = "open", limit: Int = 20): String {
        val full = resolveRepo(repo)
        val arr = check(ConnectorHttp.get(SERVICE, "$BASE/repos/$full/pulls?state=$state&per_page=${limit.coerceIn(1, 100)}", headers()), "repo '$full'").jsonArray()
        if (arr.length() == 0) return "$full pe koi $state pull request nahi hai."
        val sb = StringBuilder("$full $state PRs:\n")
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            sb.appendLine("#${p.optInt("number")} ${p.optString("title")} — ${p.optJSONObject("head")?.optString("ref")} → ${p.optJSONObject("base")?.optString("ref")} by ${p.optJSONObject("user")?.optString("login")}${if (p.optBoolean("draft")) " [draft]" else ""} · ${p.optString("updated_at").take(10)}")
        }
        return sb.toString().trimEnd()
    }

    private suspend fun prJson(full: String, number: Int): JSONObject =
        check(ConnectorHttp.get(SERVICE, "$BASE/repos/$full/pulls/$number", headers()), "pull request #$number").json()

    private suspend fun checksSummary(full: String, sha: String): String {
        val resp = ConnectorHttp.get(SERVICE, "$BASE/repos/$full/commits/$sha/check-runs?per_page=50", headers())
        if (!resp.ok) return "unknown"
        val runs = resp.json().optJSONArray("check_runs") ?: return "none"
        if (runs.length() == 0) return "none"
        var ok = 0; var fail = 0; var pending = 0
        for (i in 0 until runs.length()) {
            val r = runs.optJSONObject(i) ?: continue
            when {
                r.optString("status") != "completed" -> pending++
                r.optString("conclusion") in setOf("success", "neutral", "skipped") -> ok++
                else -> fail++
            }
        }
        return "$ok passed, $fail failed, $pending pending"
    }

    suspend fun readPr(repo: String, number: Int): String {
        val full = resolveRepo(repo)
        val p = prJson(full, number)
        val sha = p.optJSONObject("head")?.optString("sha").orEmpty()
        val checks = if (sha.isNotBlank()) checksSummary(full, sha) else "unknown"
        val mergeable = when {
            p.isNull("mergeable") -> "being computed"
            p.optBoolean("mergeable") -> "yes"
            else -> "NO (conflicts)"
        }
        return buildString {
            appendLine("PR #${p.optInt("number")} ${p.optString("title")} (${p.optString("state")}${if (p.optBoolean("merged")) ", merged" else ""}) by ${p.optJSONObject("user")?.optString("login")}")
            appendLine("${p.optJSONObject("head")?.optString("ref")} → ${p.optJSONObject("base")?.optString("ref")} | ${p.optInt("commits")} commits, ${p.optInt("changed_files")} files, +${p.optInt("additions")}/-${p.optInt("deletions")}")
            appendLine("Mergeable: $mergeable | Checks: $checks")
            appendLine(p.optString("body").takeIf { it != "null" }.orEmpty().take(3000))
            append(p.optString("html_url"))
        }
    }

    suspend fun createPr(repo: String, title: String, head: String, base: String?, body: String?, draft: Boolean): String {
        val full = resolveRepo(repo)
        val payload = JSONObject().put("title", title.trim()).put("head", head.trim()).put("base", base?.takeIf { it.isNotBlank() } ?: defaultBranch(full)).put("draft", draft)
        if (!body.isNullOrBlank()) payload.put("body", body)
        val j = check(ConnectorHttp.postJson(SERVICE, "$BASE/repos/$full/pulls", payload, headers()), "repo/branch").json()
        return "PR #${j.optInt("number")} opened: ${j.optString("html_url")}"
    }

    /** Two-step merge: without [confirmed] returns a preview; with it, merges. */
    suspend fun mergePr(repo: String, number: Int, confirmed: Boolean, method: String?): String {
        val full = resolveRepo(repo)
        val p = prJson(full, number)
        if (p.optBoolean("merged")) return "PR #$number pehle se merged hai."
        if (!confirmed) {
            val sha = p.optJSONObject("head")?.optString("sha").orEmpty()
            return "Merge hone wala hai: PR #$number '${p.optString("title")}' — ${p.optJSONObject("head")?.optString("ref")} → ${p.optJSONObject("base")?.optString("ref")}, " +
                "${p.optInt("commits")} commits, ${p.optInt("changed_files")} files. Checks: ${checksSummary(full, sha)}. Mergeable: ${if (p.optBoolean("mergeable")) "yes" else "unclear"}.\n\n" +
                "User ko ye padh ke sunao aur haan-na poochho. Haan bole tabhi github_merge_pr ko confirmed=true ke saath dobara call karo."
        }
        val payload = JSONObject().put("merge_method", method?.takeIf { it in setOf("merge", "squash", "rebase") } ?: "merge")
        val resp = ConnectorHttp.putJson(SERVICE, "$BASE/repos/$full/pulls/$number/merge", payload, headers())
        if (resp.code == 405 || resp.code == 409) throw ConnectorException("PR #$number merge nahi ho saka: ${ConnectorHttp.errorSnippet(resp.body)}", resp.code)
        val j = check(resp, "pull request #$number").json()
        return "PR #$number merged (${j.optString("sha").take(7)}): ${j.optString("message")}"
    }

    suspend fun recentCommits(repo: String, branch: String?, limit: Int = 15, path: String? = null): String {
        val full = resolveRepo(repo)
        var url = "$BASE/repos/$full/commits?per_page=${limit.coerceIn(1, 100)}"
        branch?.takeIf { it.isNotBlank() }?.let { url += "&sha=${URLEncoder.encode(it, "UTF-8")}" }
        path?.takeIf { it.isNotBlank() }?.let { url += "&path=${URLEncoder.encode(it, "UTF-8")}" }
        val arr = check(ConnectorHttp.get(SERVICE, url, headers()), "repo/branch").jsonArray()
        val sb = StringBuilder("$full recent commits:\n")
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val commit = c.optJSONObject("commit") ?: continue
            val author = c.optJSONObject("author")?.optString("login")?.ifBlank { null } ?: commit.optJSONObject("author")?.optString("name").orEmpty()
            sb.appendLine("${c.optString("sha").take(7)} ${commit.optString("message").lineSequence().first().take(100)} — $author · ${commit.optJSONObject("author")?.optString("date")?.take(10)}")
        }
        return sb.toString().trimEnd()
    }

    suspend fun readCommit(repo: String, sha: String, maxPatchChars: Int = 6000): String {
        val full = resolveRepo(repo)
        val c = check(ConnectorHttp.get(SERVICE, "$BASE/repos/$full/commits/${URLEncoder.encode(sha.trim(), "UTF-8")}", headers()), "commit '$sha'").json()
        val commit = c.optJSONObject("commit") ?: JSONObject()
        val files = c.optJSONArray("files") ?: JSONArray()
        val sb = StringBuilder()
        sb.appendLine("${c.optString("sha").take(7)} by ${commit.optJSONObject("author")?.optString("name")} · ${commit.optJSONObject("author")?.optString("date")?.take(10)}")
        sb.appendLine(commit.optString("message"))
        sb.appendLine("Files (${files.length()}): +${c.optJSONObject("stats")?.optInt("additions")}/-${c.optJSONObject("stats")?.optInt("deletions")}")
        var budget = maxPatchChars
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            sb.appendLine("--- ${f.optString("status")} ${f.optString("filename")} (+${f.optInt("additions")}/-${f.optInt("deletions")})")
            val patch = f.optString("patch")
            if (patch.isNotBlank() && budget > 0) {
                val cut = patch.take(budget)
                sb.appendLine(cut)
                budget -= cut.length
            }
        }
        return sb.toString().trimEnd()
    }

    suspend fun searchCode(query: String, repo: String?, limit: Int = 10): String {
        val scope = if (!repo.isNullOrBlank()) "repo:${resolveRepo(repo)}" else "user:${login()}"
        val q = URLEncoder.encode("$query $scope", "UTF-8")
        val j = check(ConnectorHttp.get(SERVICE, "$BASE/search/code?q=$q&per_page=${limit.coerceIn(1, 30)}", headers("application/vnd.github.text-match+json"))).json()
        val items = j.optJSONArray("items") ?: JSONArray()
        if (items.length() == 0) return "'$query' ke liye kuch nahi mila ($scope)."
        val sb = StringBuilder("${j.optInt("total_count")} matches for '$query':\n")
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            sb.appendLine("${it.optJSONObject("repository")?.optString("full_name")}:${it.optString("path")}")
            val tm = it.optJSONArray("text_matches") ?: continue
            for (k in 0 until minOf(tm.length(), 2)) {
                val frag = tm.optJSONObject(k)?.optString("fragment").orEmpty()
                frag.lineSequence().filter { l -> query.split(' ').any { w -> w.isNotBlank() && l.contains(w, true) } }.take(3)
                    .forEach { l -> sb.appendLine("    ${l.trim().take(160)}") }
            }
        }
        return sb.toString().trimEnd()
    }

    suspend fun listReleases(repo: String, limit: Int = 10): String {
        val full = resolveRepo(repo)
        val arr = check(ConnectorHttp.get(SERVICE, "$BASE/repos/$full/releases?per_page=${limit.coerceIn(1, 50)}", headers()), "repo '$full'").jsonArray()
        if (arr.length() == 0) return "$full pe koi release nahi hai."
        val sb = StringBuilder("$full releases:\n")
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val assets = r.optJSONArray("assets")
            var downloads = 0
            if (assets != null) for (k in 0 until assets.length()) downloads += assets.optJSONObject(k)?.optInt("download_count") ?: 0
            sb.appendLine("${r.optString("tag_name")} ${r.optString("name").takeIf { it.isNotBlank() && it != r.optString("tag_name") }?.let { "— $it " } ?: ""}· ${r.optString("published_at").take(10)}${if (r.optBoolean("prerelease")) " [pre-release]" else ""} · $downloads downloads")
        }
        return sb.toString().trimEnd()
    }

    suspend fun actionsStatus(repo: String, limit: Int = 8): String {
        val full = resolveRepo(repo)
        val j = check(ConnectorHttp.get(SERVICE, "$BASE/repos/$full/actions/runs?per_page=${limit.coerceIn(1, 30)}", headers()), "repo '$full'").json()
        val runs = j.optJSONArray("workflow_runs") ?: JSONArray()
        if (runs.length() == 0) return "$full pe koi Actions run nahi hai."
        val sb = StringBuilder("$full Actions:\n")
        for (i in 0 until runs.length()) {
            val r = runs.optJSONObject(i) ?: continue
            val concl = r.optString("conclusion").takeIf { it.isNotBlank() && it != "null" } ?: r.optString("status")
            sb.appendLine("run ${r.optLong("id")} ${r.optString("name")} on ${r.optString("head_branch")}: $concl · ${r.optString("updated_at").take(16).replace('T', ' ')}")
        }
        val failed = (0 until runs.length()).mapNotNull { runs.optJSONObject(it) }.firstOrNull { it.optString("conclusion") == "failure" }
        if (failed != null) sb.append("Run ${failed.optLong("id")} fail hua hai — log padhne ke liye github_actions_log bolo.")
        return sb.toString().trimEnd()
    }

    suspend fun actionsLog(repo: String, runId: Long?, tailChars: Int = 3000): String {
        val full = resolveRepo(repo)
        val id = runId ?: run {
            val j = check(ConnectorHttp.get(SERVICE, "$BASE/repos/$full/actions/runs?status=failure&per_page=1", headers()), "repo '$full'").json()
            j.optJSONArray("workflow_runs")?.optJSONObject(0)?.optLong("id") ?: return "$full pe koi failed run nahi mila."
        }
        val jobs = check(ConnectorHttp.get(SERVICE, "$BASE/repos/$full/actions/runs/$id/jobs?per_page=50", headers()), "run $id").json().optJSONArray("jobs") ?: JSONArray()
        val job = (0 until jobs.length()).mapNotNull { jobs.optJSONObject(it) }.let { list ->
            list.firstOrNull { it.optString("conclusion") == "failure" } ?: list.lastOrNull()
        } ?: return "Run $id me koi job nahi mili."
        val failedStep = job.optJSONArray("steps")?.let { s -> (0 until s.length()).mapNotNull { s.optJSONObject(it) }.firstOrNull { it.optString("conclusion") == "failure" } }
        val resp = ConnectorHttp.get(SERVICE, "$BASE/repos/$full/actions/jobs/${job.optLong("id")}/logs", headers())
        val log = if (resp.ok) resp.body else "(log download fail: HTTP ${resp.code})"
        val tail = if (log.length > tailChars) log.takeLast(tailChars) else log
        return "Run $id · job '${job.optString("name")}' (${job.optString("conclusion")})${failedStep?.let { " · failed step: ${it.optString("name")}" } ?: ""}\n--- log tail ---\n$tail"
    }

    suspend fun createGist(description: String?, fileName: String, content: String, public: Boolean): String {
        val payload = JSONObject().apply {
            put("description", description.orEmpty())
            put("public", public)
            put("files", JSONObject().put(fileName.ifBlank { "snippet.txt" }, JSONObject().put("content", content)))
        }
        val j = check(ConnectorHttp.postJson(SERVICE, "$BASE/gists", payload, headers())).json()
        return "Gist created: ${j.optString("html_url")}"
    }

    suspend fun myWork(): String {
        val me = login()
        suspend fun search(q: String): List<String> {
            val j = check(ConnectorHttp.get(SERVICE, "$BASE/search/issues?q=${URLEncoder.encode(q, "UTF-8")}&per_page=15&sort=updated", headers())).json()
            val items = j.optJSONArray("items") ?: JSONArray()
            return (0 until items.length()).mapNotNull { items.optJSONObject(it) }.map {
                val repo = it.optString("repository_url").substringAfter("/repos/")
                "$repo#${it.optInt("number")} ${it.optString("title")} · ${it.optString("updated_at").take(10)}"
            }
        }
        val assigned = search("is:open is:issue assignee:$me")
        val reviews = search("is:open is:pr review-requested:$me")
        val myPrs = search("is:open is:pr author:$me")
        if (assigned.isEmpty() && reviews.isEmpty() && myPrs.isEmpty()) return "GitHub pe tumhare naam kuch pending nahi hai."
        return buildString {
            if (assigned.isNotEmpty()) { appendLine("Issues assigned to you (${assigned.size}):"); assigned.forEach { appendLine("  $it") } }
            if (reviews.isNotEmpty()) { appendLine("Reviews requested (${reviews.size}):"); reviews.forEach { appendLine("  $it") } }
            if (myPrs.isNotEmpty()) { appendLine("Your open PRs (${myPrs.size}):"); myPrs.forEach { appendLine("  $it") } }
        }.trimEnd()
    }

    /**
     * Puts a local project folder on GitHub: creates the repo if missing and commits every file
     * (one commit per file via the contents API). Returns the repo URL.
     */
    suspend fun shareRepo(folder: File, repoName: String, isPrivate: Boolean, description: String?): String {
        if (!folder.exists() || !folder.isDirectory) throw ConnectorException("Folder nahi mila: ${folder.path}")
        val me = login()
        val name = repoName.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { folder.name }
        val full = "$me/$name"
        val existing = ConnectorHttp.get(SERVICE, "$BASE/repos/$full", headers())
        var branch = "main"
        if (existing.code == 404) {
            val payload = JSONObject().put("name", name).put("private", isPrivate).put("auto_init", false)
            if (!description.isNullOrBlank()) payload.put("description", description)
            check(ConnectorHttp.postJson(SERVICE, "$BASE/user/repos", payload, headers()))
        } else {
            branch = check(existing, "repo '$full'").json().optString("default_branch", "main")
        }
        val root = folder.canonicalFile
        val files = root.walkTopDown().onEnter { d -> d == root || d.name !in SKIP_DIRS }.filter { it.isFile && it.length() <= MAX_FILE_BYTES }.take(300).toList()
        if (files.isEmpty()) throw ConnectorException("Folder me koi file nahi mili.")
        var pushed = 0
        var skipped = 0
        for (f in files) {
            val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
            try {
                val existingFile = try { getContent(full, rel, branch) } catch (e: ConnectorException) { if (e.httpCode == 404 || e.httpCode == 409) null else throw e }
                val body = JSONObject().apply {
                    put("message", "${if (existingFile != null) "Update" else "Add"} $rel")
                    put("content", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
                    put("branch", branch)
                    existingFile?.optString("sha")?.takeIf { it.isNotBlank() }?.let { put("sha", it) }
                }
                val resp = ConnectorHttp.putJson(SERVICE, "$BASE/repos/$full/contents/${safePath(rel)}", body, headers())
                if (resp.ok) pushed++ else skipped++
            } catch (_: ConnectorException) {
                skipped++
            }
        }
        return "Repo https://github.com/$full ($branch): $pushed files committed${if (skipped > 0) ", $skipped skipped" else ""}. Vercel/Netlify pe isi repo ko import karke deploy kar sakte ho."
    }
}
