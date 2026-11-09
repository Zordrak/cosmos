package com.cosmos.screensaver.ui

import android.content.Context
import com.cosmos.screensaver.R
import com.cosmos.screensaver.settings.SetupIssue
import com.cosmos.screensaver.settings.SetupState

/** The one thing a setup notice can send the user to do. */
internal enum class SetupFix { CHOOSE_SCREENSAVER, REVIEW_CHOICES, REVIEW_SIZE, TRY_AGAIN }

/** Wording and action for one setup issue; [fix] is null when there is nothing the user can do from Home. */
internal class SetupNotice(val message: String, val fix: SetupFix?, val fixLabel: String?)

internal object SetupNotices {
    /** Which action resolves [issue], or null when none is offered. */
    fun fixFor(issue: SetupIssue): SetupFix? = when (issue) {
        SetupIssue.UNREADABLE -> null
        SetupIssue.SCREENSAVER_NOT_SELECTED,
        SetupIssue.SCREENSAVER_OTHER,
        SetupIssue.SCREENSAVER_DISABLED -> SetupFix.CHOOSE_SCREENSAVER
        SetupIssue.NO_SOURCES,
        SetupIssue.NO_MATCHING_PICTURES -> SetupFix.REVIEW_CHOICES
        SetupIssue.LOW_SPACE,
        SetupIssue.FAVOURITES_FILL_SPACE -> SetupFix.REVIEW_SIZE
        SetupIssue.CANNOT_REACH_SOURCES -> SetupFix.TRY_AGAIN
    }

    /** The notice to show for [state], or null while everything is fine or still settling. */
    fun of(context: Context, state: SetupState): SetupNotice? {
        val issue = (state as? SetupState.Issue) ?: return null
        val message = when (issue.issue) {
            SetupIssue.UNREADABLE -> context.getString(R.string.home_unreadable)
            SetupIssue.SCREENSAVER_NOT_SELECTED -> context.getString(R.string.setup_screensaver_not_selected)
            SetupIssue.SCREENSAVER_OTHER -> context.getString(R.string.setup_screensaver_other)
            SetupIssue.SCREENSAVER_DISABLED -> context.getString(R.string.setup_screensaver_disabled)
            SetupIssue.NO_SOURCES -> context.getString(R.string.home_no_sources)
            SetupIssue.NO_MATCHING_PICTURES -> context.getString(R.string.setup_no_matching)
            SetupIssue.LOW_SPACE -> context.getString(R.string.setup_low_space)
            SetupIssue.FAVOURITES_FILL_SPACE -> context.getString(R.string.setup_favourites_fill_space)
            SetupIssue.CANNOT_REACH_SOURCES ->
                context.resources.getQuantityString(R.plurals.setup_cannot_reach, issue.days, issue.days)
        }
        val fix = fixFor(issue.issue)
        val label = fix?.let {
            context.getString(
                when (it) {
                    SetupFix.CHOOSE_SCREENSAVER -> R.string.setup_fix_screensaver
                    SetupFix.REVIEW_CHOICES -> R.string.setup_fix_what_to_show
                    SetupFix.REVIEW_SIZE -> R.string.setup_fix_space
                    SetupFix.TRY_AGAIN -> R.string.setup_fix_retry
                }
            )
        }
        return SetupNotice(message, fix, label)
    }
}
