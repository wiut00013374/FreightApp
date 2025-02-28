package com.example.freightapp.repos

import com.example.freightapp.model.Order

interface OrderActionListener {
    fun onEditOrder(order: Order)
    fun onDeleteOrder(order: Order)
    fun onContactDriver(order: Order)
}
