package com.google.firebase.codelab.friendlychat

/**
 * Created by danielvilha on 2019-07-06
 */
class FriendlyMessage(var id: String, val text: String, val name: String, val photoUrl: String, val imageUrl: String) {
    constructor() : this("", "", "", "", "")
}