package com.example.agenticassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agenticassistant.network.AgentResponse
import com.example.agenticassistant.network.RetrofitClient
import com.example.agenticassistant.network.ScheduleResponse
import com.example.agenticassistant.ui.theme.AgenticAssistantTheme
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {

            AgenticAssistantTheme {

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->

                    AgentScreen(modifier = Modifier.padding(innerPadding))

                }

            }

        }

    }

}


@Composable
fun AgentScreen(modifier: Modifier = Modifier) {

    var resultText by remember { mutableStateOf("") }

    var meetLink by remember { mutableStateOf("") }

    var isLoading by remember { mutableStateOf(false) }


    Column(

        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),

        horizontalAlignment = Alignment.CenterHorizontally,

        verticalArrangement = Arrangement.Top

    ) {

        Spacer(modifier = Modifier.height(24.dp))


        Button(

            onClick = {

                isLoading = true

                resultText = ""

                meetLink = ""

                RetrofitClient.api.runAgent().enqueue(object : Callback<AgentResponse> {

                    override fun onResponse(

                        call: Call<AgentResponse>,

                        response: Response<AgentResponse>

                    ) {

                        isLoading = false

                        if (response.isSuccessful) {

                            val body = response.body()

                            if (body != null) {

                                val sb = StringBuilder()


                                body.summaries?.let { summaries ->

                                    sb.appendLine("📋 Summaries:")

                                    summaries.forEachIndexed { index, summary ->

                                        sb.appendLine("${index + 1}. $summary")

                                    }

                                }


                                if (body.meeting_required == true) {

                                    sb.appendLine()

                                    sb.appendLine("📅 Meeting Required!")

                                    body.proposed_slots?.let { slots ->

                                        sb.appendLine("Proposed Slots:")

                                        slots.forEach {

                                            sb.appendLine("  • $it")

                                        }

                                    }

                                }

                                resultText = sb.toString()

                            } else {

                                resultText = "Empty response from server."

                            }

                        } else {

                            resultText = "Error: ${response.code()} ${response.message()}"

                        }

                    }


                    override fun onFailure(call: Call<AgentResponse>, t: Throwable) {

                        isLoading = false

                        resultText = "Network error: ${t.message}"

                    }

                })

            },

            enabled = !isLoading

        ) {

            Text("Scan Inbox")

        }


        Spacer(modifier = Modifier.height(16.dp))


        Button(

            onClick = {

                RetrofitClient.api.scheduleMeeting().enqueue(object : Callback<ScheduleResponse> {

                    override fun onResponse(

                        call: Call<ScheduleResponse>,

                        response: Response<ScheduleResponse>

                    ) {

                        if (response.isSuccessful) {

                            val body = response.body()

                            if (body != null) {

                                meetLink = body.meet_link

                            }

                        } else {

                            meetLink = "Error creating meeting"

                        }

                    }


                    override fun onFailure(call: Call<ScheduleResponse>, t: Throwable) {

                        meetLink = "Failed to create meeting: ${t.message}"

                    }

                })

            }

        ) {

            Text("Schedule Meeting")

        }


        Spacer(modifier = Modifier.height(24.dp))


        if (isLoading) {

            CircularProgressIndicator()

        }


        if (resultText.isNotEmpty()) {

            Text(

                text = resultText,

                fontSize = 16.sp,

                fontWeight = FontWeight.Normal,

                color = Color.Black

            )

        }


        Spacer(modifier = Modifier.height(20.dp))


        if (meetLink.isNotEmpty()) {

            Text(

                text = "Meeting Created!",

                fontWeight = FontWeight.Bold,

                fontSize = 18.sp

            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(

                text = meetLink,

                color = Color.Blue,

                fontSize = 16.sp

            )

        }

    }

}


@Preview(showBackground = true)
@Composable
fun AgentScreenPreview() {

    AgenticAssistantTheme {

        AgentScreen()

    }

}