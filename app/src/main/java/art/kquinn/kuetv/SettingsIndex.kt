package art.kquinn.kuetv

/**
 * Search all settings (Kevin, 25 November 2026): every part, section and setting in KueTV's Settings, so typing a word
 * finds where it lives. Made from the Settings screen itself on 25 November 2026; a new setting needs a line here too.
 * [page] is the Settings part ("" for one of the groups on the first page), [fold] the section inside it or, for a
 * group, the group's name; [label] the setting's own name.
 */
internal object SettingsIndex {
    class Entry(val page: String, val fold: String?, val label: String) {
        /** Where it is, as the screen shows it: "Watching › Subtitles". */
        val trail: String get() = listOf(page, fold).filter { !it.isNullOrBlank() }.joinToString(" › ")
    }

    val all = listOf(
        Entry("Your things", null, "Your things"),
        Entry("Your things", null, "History"),
        Entry("Your things", null, "Clear history"),
        Entry("Your things", null, "Profiles"),
        Entry("Your things", null, "Add a profile"),
        Entry("Your things", null, "Add a kids profile"),
        Entry("Your things", null, "Switch profile"),
        Entry("Your things", null, "PIN"),
        Entry("Your channels", null, "Your channels"),
        Entry("Your channels", "Switch provider", "Switch provider"),
        Entry("Your channels", "Also in your lists", "Also in your lists"),
        Entry("Your channels", null, "Change provider details"),
        Entry("Your channels", null, "Add another provider"),
        Entry("Your channels", null, "Reload channels and movies"),
        Entry("Your channels", null, "Subscription"),
        Entry("Your channels", null, "DNS server"),
        Entry("Your channels", null, "Tidy channel names"),
        Entry("Your channels", null, "Categories: show, hide and order"),
        Entry("Your channels", "Free channels", "Free channels"),
        Entry("Your channels", "Free channels", "Free channel lists"),
        Entry("Your channels", "TV guide", "TV guide"),
        Entry("Your channels", "TV guide", "Guide times"),
        Entry("Your channels", "Free TV guides", "Free TV guides"),
        Entry("Your channels", "Free TV guides", "Add a guide by its web address"),
        Entry("Your channels", "Favourites", "Favourites"),
        Entry("Backup and sync", null, "Backup and sync"),
        Entry("Backup and sync", "Google Drive", "Google Drive"),
        Entry("Backup and sync", "Google Drive", "Sign out of Google Drive"),
        Entry("Backup and sync", "Google Drive", "Sign in to Google Drive"),
        Entry("Backup and sync", "Backup", "Backup"),
        Entry("Backup and sync", "Backup", "Back up now"),
        Entry("Backup and sync", "Backup", "Back up by itself"),
        Entry("Backup and sync", "Backup", "Restore from Google Drive"),
        Entry("Backup and sync", "Devices sync", "Devices sync"),
        Entry("Backup and sync", "Devices sync", "Keep in step"),
        Entry("Movies and series", null, "Movies and series"),
        Entry("Movies and series", null, "Stremio account"),
        Entry("Movies and series", null, "My add-ons"),
        Entry("Movies and series", null, "Add an add-on or channel list by its link"),
        Entry("Watching", null, "Watching"),
        Entry("Watching", "Subtitles", "Subtitles"),
        Entry("Watching", "Subtitles", "Subtitle language"),
        Entry("Watching", "Subtitles", "Subtitle size"),
        Entry("Watching", "Subtitles", "Subtitle colour"),
        Entry("Watching", "Subtitles", "Subtitle background"),
        Entry("Watching", "Subtitles", "Subtitle position"),
        Entry("Watching", "Live TV", "Live TV"),
        Entry("Watching", "Live TV", "When KueTV opens"),
        Entry("Watching", "Live TV", "Pause and rewind live TV"),
        Entry("Watching", "Live TV", "Play the next episode by itself"),
        Entry("Watching", "Live TV", "Catch-up plays"),
        Entry("Watching", "Live TV", "Small TV while browsing"),
        Entry("Watching", "Live TV", "Sounds as you move around"),
        Entry("Watching", "The remote's spare buttons", "The remote's spare buttons"),
        Entry("Watching", "Sound and picture", "Sound and picture"),
        Entry("Watching", "Sound and picture", "Surround sound"),
        Entry("Watching", "Sound and picture", "Night mode"),
        Entry("Watching", "Sound and picture", "Volume boost"),
        Entry("Watching", "Sound and picture", "Picture quality"),
        Entry("Watching", "Sound and picture", "Player"),
        Entry("Watching", "Sound and picture", "VLC smooths combed lines"),
        Entry("Watching", "Sound and picture", "Match the TV to the movie"),
        Entry("Watching", "Screen", "Screen"),
        Entry("Watching", "Screen", "Text size"),
        Entry("Watching", "Screen", "Reset text size, subtitles and small TV"),
        Entry("Watching", "Screen", "Small picture when you leave KueTV"),
        Entry("Look and feel", null, "Look and feel"),
        Entry("Look and feel", null, "Language"),
        Entry("Look and feel", null, "Colour scheme"),
        Entry("Look and feel", null, "Colours"),
        Entry("Look and feel", null, "Highlight colour"),
        Entry("Look and feel", null, "Outline round what the remote is on"),
        Entry("Look and feel", null, "Glass look"),
        Entry("Look and feel", null, "Back to the usual look"),
        Entry("This TV", null, "This TV"),
        Entry("This TV", "Aerial TV", "Aerial TV"),
        Entry("This TV", "Aerial TV", "Watch aerial TV"),
        Entry("This TV", "Aerial TV", "Offer aerial TV when the internet goes"),
        Entry("This TV", "Aerial TV", "Aerial channel list"),
        Entry("This TV", "Aerial TV", "Your own aerial numbers"),
        Entry("This TV", "Aerial TV", "The aerial channels"),
        Entry("This TV", "Quick settings", "Quick settings"),
        Entry("This TV", "Quick settings", "Picture, sound and sleep timer"),
        Entry("This TV", "Quick settings", "Put the TV to sleep"),
        Entry("This TV", "Quick settings", "Display mirroring"),
        Entry("This TV", "Quick settings", "The TV's inputs"),
        Entry("This TV", "Quick settings", "The TV's programme guide"),
        Entry("This TV", "The TV's settings", "The TV's settings"),
        Entry("This TV", "The TV's settings", "All Fire TV settings"),
        Entry("This TV", "The TV's settings", "Get more apps"),
        Entry("This TV", "Storage", "Storage"),
        Entry("This TV", "Storage", "Clear space"),
        Entry("This TV", "Apps on this TV, biggest first", "Apps on this TV, biggest first"),
        Entry("This TV", "Channel player apps", "Channel player apps"),
        Entry("This TV", "Channel player apps", "Stop adding"),
        Entry("Help and about", null, "Help and about"),
        Entry("Help and about", null, "Health check"),
        Entry("Help and about", null, "Report for Claude"),
        Entry("Help and about", null, "About and updates · version"),
        Entry("VPN", null, "VPN"),
        Entry("", "Notifications", "Programme reminders"),
        Entry("", "Privacy and storage", "Permissions"),
        Entry("", "Privacy and storage", "What leaves this TV"),
        Entry("", "Your Kue apps", "KueSounds"),
        Entry("", "Your Kue apps", "KueVault"),
    )

    /** The settings whose name, section or part hold every word typed, best first: the name itself before the trail. */
    fun find(typed: String): List<Entry> {
        val words = typed.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return all.filter { e -> val text = (e.label + " " + e.trail).lowercase(); words.all { it in text } }
            .sortedBy { e -> if (words.all { it in e.label.lowercase() }) 0 else 1 }
            .take(30)
    }
}
