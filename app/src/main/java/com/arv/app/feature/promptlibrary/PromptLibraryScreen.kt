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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.material3.TextButton
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arv.app.core.model.PromptStatus

@Composable
fun PromptLibraryScreen(
    modifier: Modifier = Modifier,
    onRecord: () -> Unit

){
    val viewModel: PromptLibraryViewModel = viewModel()

    val savedPrompts by viewModel.savedPrompts.collectAsStateWithLifecycle()

    val prompts by viewModel.prompts.collectAsStateWithLifecycle()

    val myQuestions by viewModel.myQuestions.collectAsStateWithLifecycle()


    var selectedCategory by remember{
        mutableStateOf("Suggested")
    }


    var showOwnQuestion by remember{
        mutableStateOf(false)
    }

    var ownQuestion by remember {
        mutableStateOf("")
    }

    var questionSaved by remember {
        mutableStateOf(false)
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
        Spacer(modifier = Modifier.height(20.dp))

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedCategory == "Suggested",
                onClick = {
                    selectedCategory = "Suggested"
                    questionSaved = false
                },
                label = {
                    Text("Suggested")
                }
            )
            FilterChip(
                selected = selectedCategory == "Childhood",
                onClick = {
                    selectedCategory = "Childhood"
                    questionSaved = false
                },
                label = {
                    Text("Childhood")
                }
            )

            FilterChip(
                selected = selectedCategory == "Food",
                onClick = {
                    selectedCategory = "Food"
                    questionSaved = false
                },
                label = {
                    Text("Food")
                }
            )

            FilterChip(
                selected = selectedCategory == "Work",
                onClick = {
                    selectedCategory = "Work"
                    questionSaved = false
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

        Spacer(modifier = Modifier.height(10.dp))

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)

            ) {
                val whyThisOneText = when (selectedCategory) {
                    "Childhood" -> "What is one childhood memory you can still picture clearly?"
                    "Food" -> "Is there a family recipe that brings back a specific memory?"
                    "Work" -> "What is something your first job taught you that stayed with you?"
                    "Hard Things" -> "What helped your family get through a difficult time?"
                    "Faith" -> "Was there a belief or tradition that helped guide your family?"
                    else -> "You mentioned a song your mother hummed. Can you try to sing it?"
                }


                Text(
                    text = "WHY THIS ONE?"
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = whyThisOneText
                )

                Spacer(modifier = Modifier.height(8.dp))

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

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onRecord
                    ) {
                        Text("Record now")
                    }
                    Button(
                        onClick = {
                           viewModel.saveWhyThisOnePrompt(whyThisOneText)
                            questionSaved = true
                        }
                    ) {
                        Text(
                            if (questionSaved) "Saved!" else "Save for later"
                        )
                    }
                }

            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        val cookingPrompt = prompts.find{
            it.text == "Who taught you to cook?"
        }

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

                    val isSaved = cookingPrompt?.status == PromptStatus.SAVED

                    OutlinedIconButton(
                        onClick = {
                            cookingPrompt?.let { prompt ->
                                if(!isSaved){
                                    viewModel.savePrompt(prompt.promptId)
                                }
                            }

                        },
                        modifier = Modifier.size(36.dp)

                    ) {
                        Text(
                            if(isSaved) "✓" else "+"
                        )

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

                    val streetPrompt = prompts.find {
                        it.text == "What did your street sound like at night?"
                    }

                    val isSaved = streetPrompt?.status == PromptStatus.SAVED

                    OutlinedIconButton(
                        onClick = {
                            streetPrompt?.let {prompt ->
                                if(!isSaved) {
                                    viewModel.savePrompt(prompt.promptId)
                                }
                            }

                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text(
                            if(isSaved) "✓" else "+"
                        )
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

                    val wordPrompt = prompts.find {
                        it.text == "What's a word your family used that nobody else did?"
                    }

                    val isSaved = wordPrompt?.status == PromptStatus.SAVED

                    OutlinedIconButton(
                        onClick = {
                            wordPrompt?.let { prompt ->
                                if(!isSaved) {
                                    viewModel.savePrompt(prompt.promptId)
                                }
                            }

                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text(
                            if(isSaved) "✓" else "+"
                        )
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

                    val dayPrompt = prompts.find {
                        it.text == "Tell me about a day you'd live again."
                    }

                    val isSaved = dayPrompt?.status == PromptStatus.SAVED

                    OutlinedIconButton(
                        onClick = {
                            dayPrompt?.let { prompt ->
                                if(!isSaved) {
                                    viewModel.savePrompt(prompt.promptId)
                                }
                            }

                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text(
                            if(isSaved) "✓" else "+"
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

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

                    val jobPrompt = prompts.find {
                        it.text == "What was your first job, and what do you remember most about it?"
                    }

                    val isSaved = jobPrompt?.status == PromptStatus.SAVED

                    OutlinedIconButton(
                        onClick = {
                            jobPrompt?.let { prompt ->
                                if (!isSaved) {
                                    viewModel.savePrompt(prompt.promptId)

                                }
                            }

                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text(
                            if(isSaved) "✓" else "+"
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

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

                    val hardThingsPrompt = prompts.find {
                        it.text == "What was one difficult time your family made it through together?"
                    }

                    val isSaved = hardThingsPrompt?.status == PromptStatus.SAVED

                    OutlinedIconButton(
                        onClick = {
                            hardThingsPrompt?.let { prompt ->
                                if(!isSaved) {
                                    viewModel.savePrompt(prompt.promptId)
                                }
                            }

                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text(
                            if (isSaved) "✓" else "+"
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

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

                    val faithPrompt = prompts.find {
                        it.text == "Was there a tradition, prayer, or belief that brought your family comfort?"
                    }

                    val isSaved = faithPrompt?.status == PromptStatus.SAVED

                    OutlinedIconButton(
                        onClick = {
                            faithPrompt?.let { prompt ->
                                if(!isSaved) {
                                    viewModel.savePrompt(prompt.promptId)
                                }
                            }

                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text(
                            if(isSaved) "✓" else "+"
                        )
                    }
                }
            }
        }

    if (myQuestions.isNotEmpty()) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "My Questions",
            style  = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        myQuestions.forEach { prompt ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ){
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ){
                    Text(
                        text = prompt.text,
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedIconButton(
                        onClick = {
                            if (prompt.status != PromptStatus.SAVED) {
                                viewModel.savePrompt(prompt.promptId)
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ){
                        Text(
                            if (prompt.status == PromptStatus.SAVED) "✓" else "+"
                        )
                    }
                }
            }
        }
    }

    if(savedPrompts.isNotEmpty()){
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Saved Questions",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        savedPrompts.forEach { prompt ->
            Card(
                modifier = Modifier.fillMaxWidth()
                    .padding(vertical = 4.dp)
            ){
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically

                ){
                    Text(
                        text = prompt.text,
                        modifier = Modifier.weight(1f)
                    )

                    TextButton(
                        onClick = {
                            viewModel.removeSavedPrompt(prompt.promptId)
                        }
                    ){
                        Text("Remove")
                    }
                }

            }
        }

   }
        Spacer(modifier = Modifier.height(16.dp))



     if(!showOwnQuestion){
         OutlinedButton(
             onClick = {
                 showOwnQuestion = true
             },
             modifier = Modifier
                 .fillMaxWidth()
                 .padding(vertical = 8.dp)
         ){
             Text("Write your own question")
         }

     }  else {
         OutlinedTextField(
             value = ownQuestion,
             onValueChange = { value: String ->
                 ownQuestion = value

             },
             modifier = Modifier.fillMaxWidth()
                 .padding(vertical = 8.dp),
             label = {
                 Text("Your question")
             },
             placeholder = {
                 Text("Type your own question...")
             }
         )

         Button(
             onClick = {
                 if (ownQuestion.isNotBlank()) {
                     viewModel.addUserPrompt(ownQuestion)
                     ownQuestion = ""
                     showOwnQuestion = false
                   }

                 },
             modifier = Modifier.fillMaxWidth()

         ){
             Text("Save question")
         }

     }
}
}



