package com.navio.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrderParserTest {

    @Test
    fun `parses partner style drop address`() {
        val order = OrderParser.parse(
            "in.swiggy.android",
            "New order assigned!",
            "Pickup: Olivers Kitchen, Drop: 12, Lake View Street, Anna Nagar, Chennai 600040"
        )
        assertEquals("DROP", order?.kind)
        assertEquals("12, Lake View Street, Anna Nagar, Chennai 600040", order?.address)
    }

    @Test
    fun `parses zomato deliver to address`() {
        val order = OrderParser.parse(
            "com.application.zomato",
            "Order assigned",
            "Deliver to 45/2, GST Road, Chromepet, Chennai"
        )
        assertEquals("DROP", order?.kind)
        assertEquals("45/2, GST Road, Chromepet, Chennai", order?.address)
    }

    @Test
    fun `parses multiline big text`() {
        val order = OrderParser.parse(
            "com.swiggy.gulerix",
            "New order",
            "Pickup: Karaikudi Restaurant\nDeliver to: 8, Gandhi Road"
        )
        assertEquals("DROP", order?.kind)
        assertEquals("8, Gandhi Road", order?.address)
    }

    @Test
    fun `parses pickup when no drop present`() {
        val order = OrderParser.parse(
            "com.swiggy.gulerix",
            "New order",
            "Pickup: Hotel Anandha Bhavan, T Nagar"
        )
        assertEquals("PICKUP", order?.kind)
        assertEquals("Hotel Anandha Bhavan, T Nagar", order?.address)
    }

    @Test
    fun `customer status notification without address returns null`() {
        val order = OrderParser.parse(
            "com.swiggy.gulerix",
            "Order update",
            "Your order from Sri Biryani Zone is out for delivery. Arriving in 12 mins"
        )
        assertNull(order)
    }

    @Test
    fun `irrelevant notifications are ignored`() {
        val order = OrderParser.parse("com.whatsapp", "Mom", "Call me when free")
        assertNull(order)
    }

    @Test
    fun `strips order id from address`() {
        val order = OrderParser.parse(
            "in.swiggy.android",
            "Order assigned #48291",
            "Deliver to 3, Cross Cut Road #48291"
        )
        assertEquals("3, Cross Cut Road", order?.address)
    }
}
