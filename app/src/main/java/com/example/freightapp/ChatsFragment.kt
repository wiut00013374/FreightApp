package com.example.freightapp

import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.freightapp.model.Chat
import com.example.freightapp.model.Message
import com.example.freightapp.repos.ChatRepository
import com.google.firebase.auth.FirebaseAuth

class ChatsFragment : Fragment() {

    companion object {
        private const val ARG_CHAT_ID = "arg_chat_id"

        fun newInstance(chatId: String? = null): ChatsFragment {
            val fragment = ChatsFragment()
            if (chatId != null) {
                val args = Bundle()
                args.putString(ARG_CHAT_ID, chatId)
                fragment.arguments = args
            }
            return fragment
        }
    }

    private var chatId: String? = null
    private var isInChatMode = false

    // Views for a specific chat conversation
    private lateinit var rvMessages: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var tvChatTitle: TextView
    private val messagesList = mutableListOf<Message>()
    private lateinit var messageAdapter: MessageAdapter

    // Views for the chat list
    private lateinit var rvChats: RecyclerView
    private val chatsList = mutableListOf<Chat>()
    private lateinit var chatAdapter: ChatAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = arguments?.getString(ARG_CHAT_ID)
        isInChatMode = chatId != null
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Use different layouts based on mode
        return if (isInChatMode) {
            // For single chat view
            val view = inflater.inflate(R.layout.fragment_chats, container, false)
            setupSingleChatView(view)
            view
        } else {
            // For chat list view
            inflater.inflate(R.layout.fragment_chats_list, container, false)
        }
    }

    private fun setupSingleChatView(view: View) {
        // Find views
        tvChatTitle = view.findViewById(R.id.tvChatTitle)
        rvMessages = view.findViewById(R.id.rvMessages)
        etMessage = view.findViewById(R.id.etMessage)
        btnSend = view.findViewById(R.id.btnSend)

        // Make sure chat input is displayed above bottom navigation
        val chatInputLayout = view.findViewById<LinearLayout>(R.id.chatInputLayout)
        val params = chatInputLayout.layoutParams as ViewGroup.MarginLayoutParams
        params.bottomMargin = resources.getDimensionPixelSize(R.dimen.bottom_nav_height) // Add a dimension in your resources
        chatInputLayout.layoutParams = params

        val chatIdValue = chatId
        if (chatIdValue != null) {
            // Get chat details for title
            ChatRepository.getChatDetails(chatIdValue) { chat ->
                if (chat != null) {
                    tvChatTitle.text = "Chat with ${chat.driverName}"
                }
            }

            // Listen for messages
            ChatRepository.listenForMessages(chatIdValue) { messages ->
                messagesList.clear()
                messagesList.addAll(messages)
                messageAdapter.notifyDataSetChanged()

                // Scroll to bottom
                if (messages.isNotEmpty()) {
                    rvMessages.scrollToPosition(messages.size - 1)
                }

                // Mark messages as read
                ChatRepository.markMessagesAsRead(chatIdValue)
            }

            // Setup message sending
            btnSend.setOnClickListener {
                val text = etMessage.text.toString().trim()
                if (text.isNotEmpty()) {
                    ChatRepository.sendMessage(chatIdValue, text) { success ->
                        if (success) {
                            etMessage.text.clear()
                        } else {
                            Toast.makeText(requireContext(), "Failed to send message", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (isInChatMode) {
            // Setup RecyclerView for messages
            rvMessages = view.findViewById(R.id.rvMessages)
            rvMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true // Messages start from the bottom
            }
            messageAdapter = MessageAdapter(messagesList, requireContext())
            rvMessages.adapter = messageAdapter
        } else {
            // Setup for chat list
            setupChatsList(view)
        }
    }

    private fun setupChatsList(view: View) {
        rvChats = view.findViewById(R.id.rvChatsList)
        rvChats.layoutManager = LinearLayoutManager(requireContext())

        // Create adapter with click listener
        chatAdapter = ChatAdapter(chatsList) { chat ->
            // Option 1: Navigate to ChatActivity
            val intent = Intent(requireContext(), ChatActivity::class.java)
            intent.putExtra("EXTRA_CHAT_ID", chat.id)
            startActivity(intent)

            // Option 2: Navigate to chat fragment (uncomment if preferred)
            /*
            val chatFragment = newInstance(chat.id)
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment, chatFragment)
                .addToBackStack(null)
                .commit()
            */
        }
        rvChats.adapter = chatAdapter

        // Get current user ID
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
        if (currentUserId != null) {
            // Listen for all chats involving the current user
            ChatRepository.listenForUserChats(currentUserId) { updatedChats ->
                chatsList.clear()
                chatsList.addAll(updatedChats)
                chatAdapter.notifyDataSetChanged()
            }
        } else {
            Toast.makeText(requireContext(), "Please sign in to view chats", Toast.LENGTH_SHORT).show()
        }
    }
}