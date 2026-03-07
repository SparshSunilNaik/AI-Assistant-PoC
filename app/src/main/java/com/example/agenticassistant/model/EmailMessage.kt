package com.example.agenticassistant.model

data class EmailMessage(
    val id: String,
    val subject: String,
    val snippet: String,
    val from: String,
    val date: String
)

