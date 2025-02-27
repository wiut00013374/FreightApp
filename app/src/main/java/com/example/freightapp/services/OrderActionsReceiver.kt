//package com.example.driverapp.services
//
//import android.app.NotificationManager
//import android.content.BroadcastReceiver
//import android.content.Context
//import android.content.Intent
//import android.util.Log
//import android.widget.Toast
//import com.google.firebase.auth.FirebaseAuth
//import com.google.firebase.firestore.FirebaseFirestore
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.tasks.await
//
///**
// * BroadcastReceiver to handle order accept/reject actions from notifications
// */
//class OrderActionsReceiver : BroadcastReceiver() {
//
//    companion object {
//        private const val TAG = "OrderActionsReceiver"
//        const val ACTION_ACCEPT_ORDER = "com.example.driverapp.ACCEPT_ORDER"
//        const val ACTION_REJECT_ORDER = "com.example.driverapp.REJECT_ORDER"
//    }
//
//    private val firestore = FirebaseFirestore.getInstance()
//    private val auth = FirebaseAuth.getInstance()
//
//    override fun onReceive(context: Context, intent: Intent) {
//        val orderId = intent.getStringExtra("order_id") ?: return
//        val action = intent.action ?: return
//
//        // Cancel the notification
//        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
//        notificationManager.cancel(orderId.hashCode())
//
//        when (action) {
//            ACTION_ACCEPT_ORDER -> {
//                Toast.makeText(context, "Accepting order...", Toast.LENGTH_SHORT).show()
//                acceptOrder(context, orderId)
//            }
//            ACTION_REJECT_ORDER -> {
//                Toast.makeText(context, "Rejecting order...", Toast.LENGTH_SHORT).show()
//                rejectOrder(context, orderId)
//            }
//        }
//    }
//
//    private fun acceptOrder(context: Context, orderId: String) {
//        val currentUserId = auth.currentUser?.uid ?: return
//
//        CoroutineScope(Dispatchers.IO).launch {
//            try {
//                // First check if the order is still available
//                val orderDoc = firestore.collection("orders").document(orderId).get().await()
//
//                // If order already has a driver, it's too late
//                if (orderDoc.getString("driverUid") != null) {
//                    showToast(context, "This order has already been accepted by another driver")
//                    return@launch
//                }
//
//                // Get the drivers contact list
//                val driversContactList = orderDoc.get("driversContactList") as? Map<String, String>
//
//                if (driversContactList == null || !driversContactList.containsKey(currentUserId)) {
//                    showToast(context, "You are not authorized to accept this order")
//                    return@launch
//                }
//
//                // Update the order with this driver and change status
//                val updates = hashMapOf<String, Any>(
//                    "driverUid" to currentUserId,
//                    "status" to "Accepted"
//                )
//
//                // Also update the driver's status in the contact list
//                val updatedList = driversContactList.toMutableMap()
//                updatedList[currentUserId] = "accepted"
//                updates["driversContactList"] = updatedList
//
//                // Update the order
//                firestore.collection("orders").document(orderId)
//                    .update(updates)
//                    .await()
//
//                // Show success message
//                showToast(context, "Order accepted successfully!")
//
//                // Launch the OrderDetailActivity to see the order details
//                val detailsIntent = Intent(context, com.example.driverapp.OrderDetailActivity::class.java).apply {
//                    putExtra("EXTRA_ORDER_ID", orderId)
//                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//                }
//                context.startActivity(detailsIntent)
//
//            } catch (e: Exception) {
//                Log.e(TAG, "Error accepting order: ${e.message}")
//                showToast(context, "Failed to accept order: ${e.message}")
//            }
//        }
//    }
//
//    private fun rejectOrder(context: Context, orderId: String) {
//        val currentUserId = auth.currentUser?.uid ?: return
//
//        CoroutineScope(Dispatchers.IO).launch {
//            try {
//                // Get the order data
//                val orderDoc = firestore.collection("orders").document(orderId).get().await()
//
//                // Get the drivers contact list
//                val driversContactList = orderDoc.get("driversContactList") as? Map<String, String>
//
//                if (driversContactList == null || !driversContactList.containsKey(currentUserId)) {
//                    showToast(context, "You are not authorized to reject this order")
//                    return@launch
//                }
//
//                // Update the driver's status in the contact list
//                val updatedList = driversContactList.toMutableMap()
//                updatedList[currentUserId] = "rejected"
//
//                // Get the current index
//                val currentIndex = orderDoc.getLong("currentDriverIndex")?.toInt() ?: 0
//
//                // Update the order with the rejected status and increment the index
//                firestore.collection("orders").document(orderId)
//                    .update(
//                        mapOf(
//                            "driversContactList" to updatedList,
//                            "currentDriverIndex" to currentIndex + 1
//                        )
//                    )
//                    .await()
//
//                showToast(context, "Order rejected")
//
//            } catch (e: Exception) {
//                Log.e(TAG, "Error rejecting order: ${e.message}")
//                showToast(context, "Failed to reject order: ${e.message}")
//            }
//        }
//    }
//
//    private fun showToast(context: Context, message: String) {
//        CoroutineScope(Dispatchers.Main).launch {
//            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
//        }
//    }
//}