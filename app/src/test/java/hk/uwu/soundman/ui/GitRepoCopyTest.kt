package hk.uwu.soundman.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GitRepoCopyTest {
    @Test
    fun dropsOwnerAndRepoFromBranchLabel() {
        assertEquals("master", GitRepoCopy.displayBranch("killerprojecte/SoundMan/master"))
        assertEquals("dev", GitRepoCopy.displayBranch("killerprojecte/SoundMan/dev"))
    }

    @Test
    fun keepsNestedBranchName() {
        assertEquals("feature/ui", GitRepoCopy.displayBranch("killerprojecte/SoundMan/feature/ui"))
    }

    @Test
    fun keepsBareBranchName() {
        assertEquals("master", GitRepoCopy.displayBranch("master"))
    }

    @Test
    fun buildsGithubRepoUrl() {
        assertEquals(
            "https://github.com/killerprojecte/SoundMan",
            GitRepoCopy.githubUrl("killerprojecte/SoundMan/master"),
        )
    }

    @Test
    fun fallsBackWhenRepoPrefixIsMissing() {
        assertEquals(
            "https://github.com/killerprojecte/SoundMan",
            GitRepoCopy.githubUrl("master"),
        )
    }
}
