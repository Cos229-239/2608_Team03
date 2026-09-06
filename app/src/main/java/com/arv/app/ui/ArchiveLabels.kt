package com.arv.app.ui

import com.arv.app.core.model.ArchiveArea

/** The four archives, named the same way on every surface. */
fun ArchiveArea.label(): String = when (this) {
    ArchiveArea.LINEAGE -> "Lineage"
    ArchiveArea.CULTURE -> "Culture"
    ArchiveArea.STORIES -> "Stories"
    ArchiveArea.HEALTH -> "Health"
}

/**
 * One line under a picker, so the choice reads as a meaning and not a tag. Health says
 * out loud what the code enforces, because a person deciding to add a health record
 * should know who will control it before they do.
 */
fun ArchiveArea.hint(): String = when (this) {
    ArchiveArea.LINEAGE -> "Names, dates, places, who was whose child."
    ArchiveArea.CULTURE -> "Recipes, songs, language, faith, the way things were done."
    ArchiveArea.STORIES -> "Voices, photographs, memories, lived experience."
    ArchiveArea.HEALTH ->
        "Conditions and causes that run in the family. Control follows the person it is " +
            "about, not whoever adds it, and the librarian never reasons across these records."
}
