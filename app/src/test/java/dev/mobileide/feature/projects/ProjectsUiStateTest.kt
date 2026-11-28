package dev.mobileide.feature.projects

import dev.mobileide.domain.model.Project
import dev.mobileide.domain.model.ProjectType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectsUiStateTest {

    private fun project(
        id: Long,
        name: String,
        location: String = "/storage/emulated/0/Documents/MobileIDE",
        type: ProjectType = ProjectType.C,
        path: String? = "/storage/emulated/0/Documents/MobileIDE/$name"
    ) = Project(
        id = id,
        name = name,
        type = type,
        path = path,
        locationName = location,
        createdAt = 1L,
        lastOpenedAt = 1L,
        updatedAt = 1L
    )

    private val projects = listOf(
        project(1, "Alpha", type = ProjectType.C),
        project(2, "beta-tool", type = ProjectType.PYTHON),
        project(3, "Gamma", location = "/sdcard/Projects", type = ProjectType.RUST),
        project(4, "Legacy", path = null)
    )

    @Test
    fun `blank query shows every project in repository order`() {
        val state = ProjectsUiState(isLoading = false, projects = projects)

        assertEquals(projects, state.visibleProjects)
    }

    @Test
    fun `query matches project name ignoring case`() {
        val state = ProjectsUiState(projects = projects, query = "BETA")

        assertEquals(listOf(projects[1]), state.visibleProjects)
    }

    @Test
    fun `query matches location name`() {
        val state = ProjectsUiState(projects = projects, query = "sdcard/projects")

        assertEquals(listOf(projects[2]), state.visibleProjects)
    }

    @Test
    fun `query matches project type label`() {
        val state = ProjectsUiState(projects = projects, query = "rust")

        assertEquals(listOf(projects[2]), state.visibleProjects)
    }

    @Test
    fun `projects awaiting relink still appear in the list and search`() {
        val state = ProjectsUiState(projects = projects, query = "legacy")

        assertEquals(listOf(projects[3]), state.visibleProjects)
        assertTrue(state.visibleProjects.single().needsRelink)
    }

    @Test
    fun `search with no match returns an empty list`() {
        val state = ProjectsUiState(projects = projects, query = "does-not-exist")

        assertTrue(state.visibleProjects.isEmpty())
    }
}
