package com.jarvis.assistant.automation.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice phrases for the integrated-protocol commands that CommandExecutor already handled
 * but CommandParser never produced (macros, Termux file write, Voice Guardian, watchlist,
 * GitHub issues, n8n), plus regressions for phrases that must keep their old meaning.
 */
class ProtocolCommandPhrasesTest {

    private fun cmd(phrase: String) = CommandParser.parse(phrase, false, false).command

    @Test
    fun runMacroPhrases() {
        assertEquals(JarvisCommand.RunMacro("morning routine"), cmd("run macro morning routine"))
        assertEquals(JarvisCommand.RunMacro("morning routine"), cmd("jarvis execute macro morning routine"))
        // Must not be swallowed by the YouTube "<x> chalao" suffix.
        assertEquals(JarvisCommand.RunMacro("morning"), cmd("macro morning chalao"))
        assertEquals(JarvisCommand.RunMacro("office"), cmd("office macro chalao"))
        assertEquals(JarvisCommand.RunMacro("backup"), cmd("macro backup run karo"))
    }

    @Test
    fun termuxWriteFilePhrases() {
        assertEquals(
            JarvisCommand.TermuxWriteFile("notes.txt", "Hello World"),
            cmd("termux file notes.txt mein likho Hello World")
        )
        assertEquals(
            JarvisCommand.TermuxWriteFile("~/todo.md", "buy milk"),
            cmd("termux file ~/todo.md me buy milk likho")
        )
        assertEquals(
            JarvisCommand.TermuxWriteFile("log.txt", "Server Started"),
            cmd("write Server Started to termux file log.txt")
        )
        assertEquals(
            JarvisCommand.TermuxWriteFile("a/b.sh", "echo hi"),
            cmd("write to termux file a/b.sh: echo hi")
        )
    }

    @Test
    fun removeFromWatchlistPhrases() {
        assertEquals(JarvisCommand.RemoveFromWatchlist("TCS"), cmd("remove tcs from watchlist"))
        assertEquals(JarvisCommand.RemoveFromWatchlist("INFY"), cmd("watchlist se infy hatao"))
        assertEquals(JarvisCommand.RemoveFromWatchlist("RELIANCE.NS"), cmd("reliance.ns ko watchlist se hata do"))
    }

    @Test
    fun listGitHubIssuesPhrases() {
        assertEquals(JarvisCommand.ListGitHubIssues("", "jarvis"), cmd("show github issues for jarvis"))
        assertEquals(JarvisCommand.ListGitHubIssues("octo", "hello-world"), cmd("list github issues in octo/hello-world"))
        assertEquals(JarvisCommand.ListGitHubIssues("", "jarvis"), cmd("github issues dikhao jarvis"))
        // (Repo names starting with "jarvis" lose that prefix to the wake-word stripping, so use another.)
        assertEquals(JarvisCommand.ListGitHubIssues("", "spotify-clone"), cmd("spotify-clone ke github issues dikhao"))
        // No repo → blank, the executor asks which repository.
        assertEquals(JarvisCommand.ListGitHubIssues("", ""), cmd("show my github issues"))
    }

    @Test
    fun createGitHubIssuePhrases() {
        assertEquals(
            JarvisCommand.CreateGitHubIssue("", "jarvis", "Login crash on start", ""),
            cmd("create github issue Login crash on start in repo jarvis")
        )
        assertEquals(
            JarvisCommand.CreateGitHubIssue("octo", "app", "Fix dark mode", ""),
            cmd("create a github issue in repo octo/app: Fix dark mode")
        )
        assertEquals(
            JarvisCommand.CreateGitHubIssue("", "", "Mic not working", ""),
            cmd("github issue banao Mic not working")
        )
        assertEquals(
            JarvisCommand.CreateGitHubIssue("", "myapp", "Mic not working", ""),
            cmd("myapp repo mein github issue banao Mic not working")
        )
        assertEquals(
            JarvisCommand.CreateGitHubIssue("", "myapp", "Mic not working", ""),
            cmd("github issue banao Mic not working, repo myapp mein")
        )
        assertEquals(
            JarvisCommand.CreateGitHubIssue("", "jarvis", "", ""),
            cmd("create github issue in repo jarvis")
        )
    }

    @Test
    fun n8nWorkflowPhrases() {
        val daily = cmd("create an n8n workflow to send me a Slack summary every morning")
        assertTrue(daily is JarvisCommand.GenerateN8nWorkflow)
        daily as JarvisCommand.GenerateN8nWorkflow
        assertEquals("send me a Slack summary every morning", daily.description)
        assertEquals("schedule", daily.triggerType)
        assertEquals("Send me a Slack summary every", daily.name)

        val hinglish = cmd("n8n workflow banao jab form submit ho tab email bhejo")
        assertTrue(hinglish is JarvisCommand.GenerateN8nWorkflow)
        assertEquals("form", (hinglish as JarvisCommand.GenerateN8nWorkflow).triggerType)

        val webhook = cmd("generate n8n workflow for new GitHub stars")
        assertEquals("webhook", (webhook as JarvisCommand.GenerateN8nWorkflow).triggerType)
    }

    @Test
    fun helperMappings() {
        assertEquals("schedule", CommandParser.inferN8nTrigger("har din subah report bhejo"))
    }

    @Test
    fun existingPhrasesKeepTheirMeaning() {
        assertEquals(JarvisCommand.PlayYouTube("believer"), cmd("believer chalao"))
        assertEquals(JarvisCommand.OpenApp("camera"), cmd("camera kholo"))
        assertEquals(JarvisCommand.ViewWatchlist, cmd("show watchlist"))
        assertEquals(JarvisCommand.AddToWatchlist("tcs"), cmd("add tcs to watchlist"))
        assertEquals(JarvisCommand.ListGitHubRepos, cmd("list github repos"))
    }
}
