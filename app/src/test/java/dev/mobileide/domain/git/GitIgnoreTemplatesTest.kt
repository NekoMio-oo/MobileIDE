package dev.mobileide.domain.git

import dev.mobileide.domain.model.ProjectType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitIgnoreTemplatesTest {

    @Test
    fun `python template includes common and language patterns`() {
        val content = GitIgnoreTemplates.forProjectType(ProjectType.PYTHON)

        assertTrue(content.contains(".mobileide/"))
        assertTrue(content.contains("__pycache__/"))
        assertTrue(content.contains("*.py[cod]"))
    }

    @Test
    fun `cpp template includes build artefacts`() {
        val content = GitIgnoreTemplates.forProjectType(ProjectType.CPP)

        assertTrue(content.contains("*.o"))
        assertTrue(content.contains("*.so"))
        assertTrue(content.contains("cmake-build-*/"))
    }

    @Test
    fun `empty projects only receive common patterns`() {
        val content = GitIgnoreTemplates.forProjectType(ProjectType.EMPTY)

        assertTrue(content.contains(".mobileide/"))
        assertFalse(content.contains("node_modules/"))
        assertFalse(content.contains("*.py[cod]"))
    }

    @Test
    fun `every template is non blank and ends with a newline`() {
        GitIgnoreTemplate.entries.forEach { template ->
            assertTrue(template.displayName.isNotBlank())
            assertTrue(template.contentWithNewline.isNotBlank())
            assertTrue(template.contentWithNewline.endsWith("\n"))
        }
    }
}
