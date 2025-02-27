//package com.example.driverapp.services
//
//import android.app.NotificationChannel
//import android.app.NotificationManager
//import android.app.PendingIntent
//import android.content.Context
//import android.content.Intent
//import android.media.RingtoneManager
//import android.os.Build
//import android.util.Log
//import androidx.core.app.NotificationCompat
//import com.example.driverapp.MainActivity
//import com.example.driverapp.OrderDetailActivity
//import com.example.driverapp.R
//import com.example.driverapp.data.Order
//import com.example.freightapp.MainActivity
//import com.example.freightapp.Order
//import com.example.freightapp.R
//import com.google.firebase.auth.FirebaseAuth
//import com.google.firebase.firestore.FirebaseFirestore
//import com.google.firebase.messaging.FirebaseMessagingService
//import com.google.firebase.messaging.RemoteMessage
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.tasks.await
//
//class DriverFirebaseMessagingService : FirebaseMessagingService() {
//
//    private val TAG = "DriverFCMService"
//    private val firestore = FirebaseFirestore.getInstance()
//    private val auth = FirebaseAuth.getInstance()
//
//    override fun onMessageReceived(remoteMessage: RemoteMessage) {
//        super.onMessageReceived(remoteMessage)
//        Log.d(TAG, "From: ${remoteMessage.from}")
//
//        // Check if message contains a data payload
//        remoteMessage.data.isNotEmpty().let {
//            Log.d(TAG, "Message data payload: ${remoteMessage.data}")
//
//            val orderId = remoteMessage.data["orderId"]
//            val notificationType = remoteMessage.data["type"] ?: "order_request"
//
//            if (orderId != null) {
//                when (notificationType) {
//                    "order_request" -> handleOrderRequest(orderId)
//                    "order_update" -> handleOrderUpdate(orderId)
//                    "chat_message" -> handleChatMessage(orderId)
//                }
//            }
//        }
//
//        // Check if message contains a notification payload
//        remoteMessage.notification?.let {
//            Log.d(TAG, "Message Notification Body: ${it.body}")
//            // If there's a notification but no data, show a generic notification
//            if (remoteMessage.data.isEmpty()) {
//                showBasicNotification(it.title ?: "New Notification", it.body ?: "Check your app for details")
//            }
//        }
//    }
//
//    private fun handleOrderRequest(orderId: String) {
//        CoroutineScope(Dispatchers.IO).launch {
//            try {
//                // Get the order details
//                val orderDoc = firestore.collection("orders").document(orderId).get().await()
//                val order = orderDoc.toObject(Order::class.java)
//
//                if (order != null) {
//                    // Show a notification with details and actions
//                    showOrderRequestNotification(orderId, order)
//
//                    // This is important - we load the order details into the driver app's local storage
//                    // so when they tap "View Details" we can show it immediately
//                    cacheOrderDetails(orderId, order)
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Error handling order request: ${e.message}")
//            }
//        }
//    }
//
//    private fun handleOrderUpdate(orderId: String) {
//        CoroutineScope(Dispatchers.IO).launch {
//            try {
//                // Get the order details
//                val orderDoc = firestore.collection("orders").document(orderId).get().await()
//                val order = orderDoc.toObject(Order::class.java)
//
//                if (order != null) {
//                    // Show a notification about the update
//                    val title = "Order Update"
//                    val message = "Order ${order.id} status changed to ${order.status}"
//                    showBasicNotification(title, message, orderId)
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Error handling order update: ${e.message}")
//            }
//        }
//    }
//
//    private fun handleChatMessage(chatId: String) {
//        CoroutineScope(Dispatchers.IO).launch {
//            try {
//                // Get the chat details
//                val chatDoc = firestore.collection("chats").document(chatId).get().await()
//
//                if (chatDoc.exists()) {
//                    val senderUid = chatDoc.getString("customerUid") ?: ""
//                    val messageText = chatDoc.getString("lastMessage") ?: "New message"
//
//                    // Get sender name
//                    val senderName = if (senderUid.isNotEmpty()) {
//                        val senderDoc = firestore.collection("users").document(senderUid).get().await()
//                        senderDoc.getString("displayName") ?: "Customer"
//                    } else "Customer"
//
//                    // Show notification
//                    val title = "Message from $senderName"
//                    showChatNotification(chatId, title, messageText)
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Error handling chat message: ${e.message}")
//            }
//        }
//    }
//
//    private fun showOrderRequestNotification(orderId: String, order: Order) {
//        val channelId = "order_requests_channel"
//        val channelName = "Order Requests"
//
//        // Create intent for viewing order details
//        val detailsIntent = Intent(this, OrderDetailActivity::class.java).apply {
//            putExtra("EXTRA_ORDER", order)
//            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
//        }
//        val detailsPendingIntent = PendingIntent.getActivity(
//            this, 0, detailsIntent,
//            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
//        )
//
//        // Create intent for accepting order
//        val acceptIntent = Intent(this, OrderActionsReceiver::class.java).apply {
//            action = OrderActionsReceiver.ACTION_ACCEPT_ORDER
//            putExtra("order_id", orderId)
//        }
//        val acceptPendingIntent = PendingIntent.getBroadcast(
//            this, 1, acceptIntent,
//            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
//        )
//
//        // Create intent for rejecting order
//        val rejectIntent = Intent(this, OrderActionsReceiver::class.java).apply {
//            action = OrderActionsReceiver.ACTION_REJECT_ORDER
//            putExtra("order_id", orderId)
//        }
//        val rejectPendingIntent = PendingIntent.getBroadcast(
//            this, 2, rejectIntent,
//            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
//        )
//
//        // Format price to two decimal places
//        val formattedPrice = String.format("$%.2f", order.totalPrice)
//
//        // Build the notification
//        val notificationBuilder = NotificationCompat.Builder(this, channelId)
//            .setSmallIcon(R.drawable.ic_notification)
//            .setContentTitle("New Order Request")
//            .setContentText("From ${order.originCity} to ${order.destinationCity}")
//            .setStyle(NotificationCompat.BigTextStyle()
//                .bigText("From: ${order.originCity}\nTo: ${order.destinationCity}\nPrice: $formattedPrice\nTruck: ${order.truckType}"))
//            .setPriority(NotificationCompat.PRIORITY_HIGH)
//            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
//            .setContentIntent(detailsPendingIntent)
//            .setAutoCancel(true)
//            .addAction(R.drawable.ic_notification, "View Details", detailsPendingIntent)
//            .addAction(R.drawable.ic_notification, "Accept", acceptPendingIntent)
//            .addAction(R.drawable.ic_notification, "Reject", rejectPendingIntent)
//
//        // Create the notification channel for Android O+
//        createNotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
//
//        // Show the notification
//        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
//        notificationManager.notify(orderId.hashCode(), notificationBuilder.build())
//    }
//
//    private fun showBasicNotification(title: String, message: String, id: String? = null) {
//        val channelId = "general_channel"
//        val channelName = "General Notifications"
//
//        // Create intent for when user taps the notification
//        val intent = Intent(this, MainActivity::class.java).apply {
//            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
//            if (id != null) {
//                putExtra("notification_id", id)
//            }
//        }
//        val pendingIntent = PendingIntent.getActivity(
//            this, 0, intent,
//            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
//        )
//
//        // Build the notification
//        val notificationBuilder = NotificationCompat.Builder(this, channelId)
//            .setSmallIcon(R.drawable.ic_notification)
//            .setContentTitle(title)
//            .setContentText(message)
//            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
//            .setContentIntent(pendingIntent)
//            .setAutoCancel(true)
//
//        // Create the notification channel for Android O+
//        createNotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
//
//        // Show the notification
//        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
//        notificationManager.notify(id?.hashCode() ?: System.currentTimeMillis().toInt(), notificationBuilder.build())
//    }
//
//    private fun showChatNotification(chatId: String, title: String, message: String) {
//        val channelId = "chat_channel"
//        val channelName = "Chat Messages"
//
//        // Create intent to open the chat
//        val intent = Intent(this, MainActivity::class.java).apply {
//            putExtra("navigate_to", "chat")
//            putExtra("chat_id", chatId)
//            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
//        }
//        val pendingIntent = PendingIntent.getActivity(
//            this, 0, intent,
//            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
//        )
//
//        // Build the notification
//        val notificationBuilder = NotificationCompat.Builder(this, channelId)
//            .setSmallIcon(R.drawable.ic_notification)
//            .setContentTitle(title)
//            .setContentText(message)
//            .setPriority(NotificationCompat.PRIORITY_HIGH)
//            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
//            .setContentIntent(pendingIntent)
//            .setAutoCancel(true)
//
//        // Create the notification channel for Android O+
//        createNotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
//
//        // Show the notification
//        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
//        notificationManager.notify(chatId.hashCode(), notificationBuilder.build())
//    }
//
//    private fun createNotificationChannel(channelId: String, channelName: String, importance: Int) {
//        // Create the NotificationChannel, but only on API 26+ (Android O) and above
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            val channel = NotificationChannel(channelId, channelName, importance).apply {
//                description = "Channel for $channelName"
//            }
//            // Register the channel with the system
//            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
//            notificationManager.createNotificationChannel(channel)
//        }
//    }
//
//    private fun cacheOrderDetails(orderId: String, order: Order) {
//        // Store the order details in shared preferences or a local database
//        // This is useful so we can display order details immediately when the driver opens the notification
//        val sharedPrefs = getSharedPreferences("order_cache", Context.MODE_PRIVATE)
//        with(sharedPrefs.edit()) {
//            putString("order_$orderId", order.toString()) // Consider using JSON serialization for proper storage
//            apply()
//        }
//    }
//
//    override fun onNewToken(token: String) {
//        super.onNewToken(token)
//        Log.d(TAG, "Refreshed FCM token: $token")
//
//        // Save the new token to Firestore
//        val user = auth.currentUser
//        if (user != null) {
//            CoroutineScope(Dispatchers.IO).launch {
//                try {
//                    // Try updating in both collections to ensure we cover all bases
//                    try {
//                        firestore.collection("users").document(user.uid)
//                            .update("fcmToken", token)
//                            .await()
//                        Log.d(TAG, "FCM Token updated in users collection")
//                    } catch (e: Exception) {
//                        Log.e(TAG, "Failed to update token in users collection: ${e.message}")
//                    }
//
//                    try {
//                        firestore.collection("drivers").document(user.uid)
//                            .update("fcmToken", token)
//                            .await()
//                        Log.d(TAG, "FCM Token updated in drivers collection")
//                    } catch (e: Exception) {
//                        Log.e(TAG, "Failed to update token in drivers collection: ${e.message}")
//                    }
//                } catch (e: Exception) {
//                    Log.e(TAG, "Error updating FCM token: ${e.message}")
//                }
//            }
//        }
//    }
//}