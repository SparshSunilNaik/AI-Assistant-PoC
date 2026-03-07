package com.example.agenticassistant.network

data class AgentResponse(
    val summaries: List<String>?,
    val meeting_required: Boolean?,
    val proposed_slots: List<String>?
)

