package thread.web.presentation.external

import kotlin.test.Test
import kotlin.test.assertEquals

/** Owner change #8: the initials an avatar shows while its picture loads or when it cannot load. SYNTHETIC logins only. */
class PullRequestPeopleTest {
    @Test
    fun initialsComeFromTheFirstTwoPartsOfTheLogin() {
        assertEquals("SR", initials("synthetic-reviewer-a"))
        assertEquals("SP", initials("synthetic_person"))
        assertEquals("SM", initials("synthetic.me"))
    }

    @Test
    fun aOnePartLoginGivesItsFirstTwoLettersOrDigits() {
        assertEquals("OC", initials("octocat"))
        assertEquals("9L", initials("9lives"))
        assertEquals("A", initials("a"))
        assertEquals("SY", initials("-synthetic"))
        assertEquals("SY", initials("synthetic[bot]"))
    }
}
