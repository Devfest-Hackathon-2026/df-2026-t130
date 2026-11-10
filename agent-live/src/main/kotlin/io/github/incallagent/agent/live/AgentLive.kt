// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.agent.live

/**
 * Speaking agent (Gemini Live): turns an approved delegation policy into the session setup
 * ([LiveMessages], [LiveInstructions], [LiveTools]) and executes the model's tool calls
 * ([ToolCallHandler], [OfferEvaluator]).
 *
 * The WebSocket connection, ephemeral tokens and the audio stream are not here yet
 * (docs/live-agent.md).
 */
object AgentLive
