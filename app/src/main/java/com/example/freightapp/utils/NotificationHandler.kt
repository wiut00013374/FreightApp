package com.example.freightapp.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.Manifest
import com.example.freightapp.HomeActivity
import com.example.freightapp.Order
import com.example.freightapp.R
import com.example.freightapp.model.Chat
import com.example.freightapp.model.Message

/**
 * Utility class for handling push notifications
 */
object NotificationHandler {

    private const val CHANNEL_ID_CHATS = "channel_chats"
    private const val CHANNEL_ID_ORDERS = "channel_orders"

    private const val NOTIFICATION_ID_CHAT = 1001
    private const val NOTIFICATION_ID_ORDER = 2001

    /**
     * Initialize notification channels (required for Android O and above)
     */
    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Create the chat notification channel
            val chatChannel = NotificationChannel(
                CHANNEL_ID_CHATS,
                "Chat Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for new chat messages"
                enableVibration(true)
            }

            // Create the order notification channel
            val orderChannel = NotificationChannel(
                CHANNEL_ID_ORDERS,
                "Order Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for order status updates"
                enableVibration(true)
            }

            // Register both channels with the system
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(chatChannel)
            notificationManager.createNotificationChannel(orderChannel)
        }
    }

    /**
     * Show a notification for a new chat message if permissions are granted
     */
    fun showChatMessageNotification(
        context: Context,
        message: Message,
        chat: Chat
    ) {
        // Check for notification permission
        if (!hasNotificationPermission(context)) {
            return
        }

        // Create an intent to open the specific chat
        val intent = Intent(context, HomeActivity::class.java).apply {
            putExtra("navigate_to", "chat")
            putExtra("chat_id", chat.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build the notification
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_CHATS)
            .setSmallIcon(R.drawable.chat_24px)
            .setContentTitle("New message from ${chat.driverName}")
            .setContentText(message.text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            // Show the notification with a try-catch to handle SecurityException
            with(NotificationManagerCompat.from(context)) {
                notify(NOTIFICATION_ID_CHAT, builder.build())
            }
        } catch (e: SecurityException) {
            // Log the error or handle it appropriately
            e.printStackTrace()
        }
    }

    /**
     * Show a notification for an order status update if permissions are granted
     */
    fun showOrderUpdateNotification(
        context: Context,
        order: Order
    ) {
        // Check for notification permission
        if (!hasNotificationPermission(context)) {
            return
        }

        // Create an intent to open the orders screen
        val intent = Intent(context, HomeActivity::class.java).apply {
            putExtra("navigate_to", "orders")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Format a message based on order status
        val statusMessage = when (order.status) {
            "Assigned" -> "A driver has been assigned to your order"
            "In Progress" -> "Your order is now in progress"
            "Delivered" -> "Your order has been delivered"
            "Cancelled" -> "Your order has been cancelled"
            else -> "Your order status has been updated to ${order.status}"
        }

        // Build the notification
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ORDERS)
            .setSmallIcon(R.drawable.history_24px)
            .setContentTitle("Order Update")
            .setContentText(statusMessage)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            // Show the notification with a try-catch to handle SecurityException
            with(NotificationManagerCompat.from(context)) {
                notify(NOTIFICATION_ID_ORDER, builder.build())
            }
        } catch (e: SecurityException) {
            // Log the error or handle it appropriately
            e.printStackTrace()
        }
    }

    /**
     * Check if notification permission is granted
     */
    private fun hasNotificationPermission(context: Context): Boolean {
        // For Android 13 (API level 33) and above, we need to check for POST_NOTIFICATIONS permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }

        // For older versions, we don't need explicit permission
        return true
    }
}