package com.oracleairescue

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LogSeverity
import java.io.File

object LocalGemmaRunner {
    @JvmStatic
    fun generate(context: Context, modelPath: String, prompt: String, systemPrompt: String): String {
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)

        @OptIn(ExperimentalApi::class)
        fun runWithMtp(): String {
            ExperimentalFlags.enableSpeculativeDecoding = true
            val cache = File(context.cacheDir, "litertlm-cache")
            cache.mkdirs()

            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU(),
                cacheDir = cache.absolutePath,
            )

            val engine = Engine(engineConfig)
            try {
                engine.initialize()
                val conversationConfig = ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt)
                )
                val conversation = engine.createConversation(conversationConfig)
                try {
                    return conversation.sendMessage(prompt).toString()
                } finally {
                    conversation.close()
                }
            } finally {
                engine.close()
            }
        }

        return runWithMtp()
    }
}
