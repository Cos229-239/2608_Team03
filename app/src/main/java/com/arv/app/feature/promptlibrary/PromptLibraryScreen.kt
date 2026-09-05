package com.arv.app.feature.promptlibrary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue



@Composable
fun PromptLibraryScreen(
    modifier: Modifier = Modifier
){
    var selectedCategory by remember{
        mutableStateOf("Suggested")
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())

    ) {
        Text(
            text = "Questions to ask",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(24.dp))



        Spacer(modifier = Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedCategory == "Suggested",
                onClick = {
                    selectedCategory = "Suggested"
                },
                label = {
                    Text("Suggested")
                }
            )
            FilterChip(
                selected = selectedCategory == "Childhood",
                onClick = {
                    selectedCategory = "Childhood"
                },
                label = {
                    Text("Childhood")
                }
            )

            FilterChip(
                selected = selectedCategory == "Food",
                onClick = {
                    selectedCategory = "Food"
                },
                label = {
                    Text("Food")
                }
            )

            FilterChip(
                selected = selectedCategory == "Work",
                onClick = {
                    selectedCategory = "Work"
                },
                label = {
                    Text("Work")
                }
            )

        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedCategory == "Hard Things",
                onClick = {
                    selectedCategory = "Hard Things"
                },
                label = {
                    Text("Hard Things")
                }
            )
            FilterChip(
                selected = selectedCategory == "Faith",
                onClick = {
                    selectedCategory = "Faith"
                },
                label = {
                    Text("Faith")
                }
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)

            ) {
                Text(
                    text = "WHY THIS ONE"
                )

                Text(
                    text = when (selectedCategory){
                        "Childhood" -> "What is one childhood memory you can still picture clearly?"
                        "Food" -> "Is there a family recipe that brings back a specific memory?"
                        "Work" -> "What is something your first job taught you that stayed with you?"
                        "Hard Things" -> "What helped your family get through a difficult time?"
                        "Faith" -> "Was there a belief or tradition that helped guide your family?"
                        else -> "You mentioned a song your mother hummed. Can you try to sing it?"
                    }
                )

                Text(
                    text = when (selectedCategory){
                        "Childhood" -> "Childhood memories can reveal details about family,home, and experiences that may otherwise be forgotten."
                        "Food" -> "Food can connect generations through recipes, traditions,celebrations, and memories shared around the table."
                        "Work" -> "Work stories can reveal important lessons,responsibilities, and experiences that shaped a person's life."
                        "Hard Things" -> "Difficult memories can preserve stories of resilience, support, and how a family overcame challenges together."
                        "Faith" -> "Beliefs and traditions can preserve the values,practices, and sources of comfort passed through a family."
                        else -> "Ruth referenced this at 06:18 in \"Sunday kitchen\" but never sang it. Melodies are the first thing lost."
                    }

                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { },
                    ) {
                        Text("Record now")
                    }
                    Button(
                        onClick = { },
                    ) {
                        Text("Save for later")
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (selectedCategory == "Suggested" || selectedCategory == "Food") {

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Who taught you to cook?"
                        )

                        Text(
                            text = "Food • often opens into migration stories"
                        )
                    }
                    OutlinedIconButton(
                        onClick = { },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("+")

                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (selectedCategory == "Suggested" || selectedCategory == "Childhood") {

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "What did your street sound like at night?"
                        )

                        Text(
                            text = "Childhood • Sounds can unlock vivid memories"
                        )
                    }
                    OutlinedIconButton(
                        onClick = { },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("+")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (selectedCategory == "Suggested" || selectedCategory == "Childhood") {

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "What's a word your family used that nobody else did?"
                        )

                        Text(
                            text = "Childhood • Family language holds unique memories"
                        )
                    }
                    OutlinedIconButton(
                        onClick = { },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("+")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (selectedCategory == "Suggested" || selectedCategory == "Childhood") {

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1F)
                    ) {
                        Text(
                            text = "Tell me about a day you'd live again."
                        )

                        Text(
                            text = "Reflection • Revisit a memory worth reliving"
                        )
                    }
                    OutlinedIconButton(
                        onClick = { },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("+")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (selectedCategory == "Suggested" || selectedCategory == "Work") {

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween

                ) {

                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "What was your first job, and what do you remember most about it?"
                        )

                        Text(
                            text = "Work • Early jobs can reveal family responsibilities and life changes"
                        )
                    }

                    OutlinedIconButton(
                        onClick = { },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("+")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (selectedCategory == "Suggested" || selectedCategory == "Hard Things") {

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween

                ) {

                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "What was one difficult time your family made it through together?"
                        )

                        Text(
                            text = "Hard Things • Challenges can reveal strength, support, and resilience"
                        )
                    }

                    OutlinedIconButton(
                        onClick = { },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("+")
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (selectedCategory == "Suggested" || selectedCategory == "Faith") {

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween

                ) {

                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Was there a tradition, prayer, or belief that brought your family comfort?"
                        )

                        Text(
                            text = "Faith • Beliefs and traditions can preserve meaningful family memories"
                        )
                    }

                    OutlinedIconButton(
                        onClick = { },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("+")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
        onClick = { },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ){
        Text("Write your own question")
    }
}
}



