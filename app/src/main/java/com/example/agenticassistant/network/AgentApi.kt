package com.example.agenticassistant.network

import retrofit2.Call
import retrofit2.http.POST

interface AgentApi {

    @POST("/agent/run")
    fun runAgent(): Call<AgentResponse>

    @POST("/schedule_meeting")
    fun scheduleMeeting(): Call<ScheduleResponse>
}