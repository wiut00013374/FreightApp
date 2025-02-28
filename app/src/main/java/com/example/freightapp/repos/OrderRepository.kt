package com.example.freightapp.repos

import com.example.freightapp.model.Order
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

object OrderRepository {
    private val firestore = FirebaseFirestore.getInstance()
    private const val COLLECTION = "orders"

    // CREATE
    fun createOrder(order: Order, onComplete: (Boolean, String?) -> Unit) {
        val docRef = firestore.collection(COLLECTION).document()
        order.id = docRef.id
        docRef.set(order)
            .addOnSuccessListener { onComplete(true, docRef.id) }
            .addOnFailureListener { onComplete(false, null) }
    }

    // READ (real-time)
    fun listenForCustomerOrders(customerUid: String, onOrdersUpdate: (List<Order>) -> Unit) {
        firestore.collection(COLLECTION)
            .whereEqualTo("uid", customerUid)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    onOrdersUpdate(emptyList())
                    return@addSnapshotListener
                }
                val orders = snapshots?.documents?.mapNotNull { doc ->
                    doc.toObject(Order::class.java)?.apply {
                        id = doc.id // assign Firestore doc ID
                    }
                } ?: emptyList()
                onOrdersUpdate(orders)
            }
    }

    // UPDATE
    fun updateOrder(orderId: String, updates: Map<String, Any>, onComplete: (Boolean) -> Unit) {
        if (orderId.isBlank()) {
            onComplete(false)
            return
        }
        firestore.collection(COLLECTION).document(orderId)
            .update(updates)
            .addOnSuccessListener { onComplete(true) }
            .addOnFailureListener { onComplete(false) }
    }

    // DELETE
    fun deleteOrder(orderId: String, onComplete: (Boolean) -> Unit) {
        if (orderId.isBlank()) {
            onComplete(false)
            return
        }
        firestore.collection(COLLECTION).document(orderId)
            .delete()
            .addOnSuccessListener { onComplete(true) }
            .addOnFailureListener { onComplete(false) }
    }

    // GET SINGLE ORDER
    fun getOrder(orderId: String, onComplete: (Order?) -> Unit) {
        if (orderId.isBlank()) {
            onComplete(null)
            return
        }

        firestore.collection(COLLECTION).document(orderId)
            .get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    val order = document.toObject(Order::class.java)?.apply {
                        id = document.id
                    }
                    onComplete(order)
                } else {
                    onComplete(null)
                }
            }
            .addOnFailureListener {
                onComplete(null)
            }
    }
}