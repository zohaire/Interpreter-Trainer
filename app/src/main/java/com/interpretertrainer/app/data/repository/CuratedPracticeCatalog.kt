package com.interpretertrainer.app.data.repository

import com.interpretertrainer.app.data.database.PracticeLibraryEntity

/**
 * Official sources are linked, never repackaged. The four-minute duration is the recommended
 * practice window; the linked UN page continues to expose the complete original statement.
 */
object CuratedPracticeCatalog {
    private const val EVENT = "UN General Assembly · 80th General Debate"
    private const val FIELD = "United Nations"
    private const val WINDOW = 4 * 60 * 1000L
    private const val TYPES = "Simultaneous,Consecutive,Shadowing,Transcription"

    val items = listOf(
        item("france", "Emmanuel Macron", "France statement", "23 September 2025", "French", "Multilateralism and peace", "Advanced", "Measured"),
        item("united-states-america", "Donald Trump", "United States statement", "23 September 2025", "English", "Security, trade and the UN", "Advanced", "Fast"),
        item("canada", "Anita Anand", "Canada statement", "29 September 2025", "English", "Collective security and diplomacy", "Intermediate", "Measured"),
        item("south-africa", "Cyril Ramaphosa", "South Africa statement", "23 September 2025", "English", "Global South and UN reform", "Intermediate", "Measured"),
        item("egypt", "Badr Abdelatty", "Egypt statement", "27 September 2025", "Arabic", "Middle East diplomacy", "Advanced", "Fast"),
        item("germany", "Johann Wadephul", "Germany statement", "27 September 2025", "English", "Multilateralism and humanitarian action", "Intermediate", "Measured"),
        item("italy", "Giorgia Meloni", "Italy statement", "24 September 2025", "Italian", "International order and migration", "Advanced", "Measured"),
        item("spain", "Felipe VI", "Spain statement", "24 September 2025", "Spanish", "Human rights and international law", "Advanced", "Measured"),
        item("portugal", "Marcelo Rebelo de Sousa", "Portugal statement", "23 September 2025", "Portuguese", "UN reform and climate action", "Advanced", "Measured"),
        item("india", "Subrahmanyam Jaishankar", "India statement", "27 September 2025", "English", "UN reform and global cooperation", "Advanced", "Fast"),
        item("pakistan", "Muhammad Shehbaz Sharif", "Pakistan statement", "26 September 2025", "English", "Regional security and multilateralism", "Advanced", "Fast"),
        item("turkiye", "Recep Tayyip Erdoğan", "Türkiye statement", "23 September 2025", "Turkish", "Peace and regional security", "Advanced", "Measured"),
        item("indonesia", "Prabowo Subianto", "Indonesia statement", "23 September 2025", "English", "Development and international cooperation", "Intermediate", "Measured"),
        item("australia", "Penny Wong", "Australia statement", "26 September 2025", "English", "Rules-based international order", "Intermediate", "Measured"),
        item("new-zealand", "Winston Peters", "New Zealand statement", "26 September 2025", "English", "Multilateral cooperation", "Intermediate", "Measured"),
        item("rwanda", "Olivier Nduhungirehe", "Rwanda statement", "25 September 2025", "English", "Peacebuilding and African development", "Advanced", "Measured"),
        item("micronesia-federated-states", "Wesley Simina", "Micronesia statement", "25 September 2025", "English", "Climate and small island states", "Intermediate", "Measured"),
        item("eritrea", "Osman Saleh Mohammed", "Eritrea statement", "29 September 2025", "English", "Sovereignty and development", "Advanced", "Measured"),
        item("jordan", "Abdullah II ibn Al Hussein", "Jordan statement", "23 September 2025", "English", "Middle East peace and refugees", "Advanced", "Measured"),
        item("brazil", "Luiz Inácio Lula da Silva", "Brazil statement", "23 September 2025", "Portuguese", "Climate, development and governance", "Advanced", "Measured")
    )

    private fun item(
        slug: String,
        speaker: String,
        title: String,
        date: String,
        language: String,
        topic: String,
        difficulty: String,
        speed: String
    ) = PracticeLibraryEntity(
        id = "un80-$slug",
        title = title,
        speaker = speaker,
        institutionEvent = EVENT,
        eventDate = date,
        language = language,
        field = FIELD,
        topic = topic,
        difficulty = difficulty,
        speakingSpeed = speed,
        interpretationTypes = TYPES,
        durationMillis = WINDOW,
        sourceUrl = "https://gadebate.un.org/en/80/$slug",
        mediaUri = null,
        sourceLabel = "United Nations General Debate",
        isOfficial = true,
        createdAt = 1_758_585_600_000L
    )
}
