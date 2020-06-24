package com.google.firebase.codelab.friendlychat

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.*
import android.widget.*
import com.bumptech.glide.Glide
import com.firebase.ui.database.FirebaseRecyclerAdapter
import com.firebase.ui.database.FirebaseRecyclerOptions
import com.firebase.ui.database.SnapshotParser
import com.google.android.gms.auth.api.Auth
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.GoogleApiClient
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import de.hdodenhof.circleimageview.CircleImageView

import kotlinx.android.synthetic.main.activity_main.*

/**
 * Created by danielvilha on 2019-07-04
 */
class MainActivity : AppCompatActivity(), GoogleApiClient.OnConnectionFailedListener {

    private var ANONYMOUS = "anonymous"
    private var mUsername: String? = null
    private var mPhotoUrl: String? = null
    private var mSharedPreferences: SharedPreferences? = null
    private var mGoogleApiClient: GoogleApiClient? = null

    private var mLinearLayoutManager: LinearLayoutManager? = null

    // Firebase instance variables
    private var mFirebaseAuth: FirebaseAuth? = null
    private var mFirebaseUser: FirebaseUser? = null
    private var mFirebaseDatabaseReference: DatabaseReference? = null
    private var mFirebaseAdapter: FirebaseRecyclerAdapter<FriendlyMessage, MessageViewHolder>? = null

    // Old
    val MESSAGES_CHILD = "messages"
    private val REQUEST_INVITE = 1
    private val REQUEST_IMAGE = 2
    private val LOADING_IMAGE_URL = "https://www.google.com/images/spin-32.gif"
    val DEFAULT_MSG_LENGTH_LIMIT = 10
    private val MESSAGE_SENT_EVENT = "message_sent"
    private val MESSAGE_URL = "http://friendlychat.firebase.google.com/message/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        // Set default username is anonymous.
        mUsername = ANONYMOUS

        mGoogleApiClient = GoogleApiClient.Builder(this)
                .enableAutoManage(this /* FragmentActivity */, this /* OnConnectionFailedListener */)
                .addApi(Auth.GOOGLE_SIGN_IN_API!!)
                .build()

        // Initialize Firebase Auth
        mFirebaseAuth = FirebaseAuth.getInstance()
        mFirebaseUser = mFirebaseAuth?.currentUser

        if (mFirebaseUser == null) {
            // Not signed in, launch the Sign In activity
            startActivity(Intent(this, SignInActivity::class.java))
            finish()
            return
        } else {
            mUsername = mFirebaseUser?.displayName
            if (mFirebaseUser?.photoUrl != null) {
                mPhotoUrl = mFirebaseUser?.photoUrl.toString()
            }
        }

        mLinearLayoutManager = LinearLayoutManager(this)
        mLinearLayoutManager!!.stackFromEnd = true
        messageRecyclerView.layoutManager = mLinearLayoutManager

        progressBar.visibility = ProgressBar.INVISIBLE

        // New child entries
        mFirebaseDatabaseReference = FirebaseDatabase.getInstance().reference
        val parser = SnapshotParser<FriendlyMessage> { snapshot ->
            val friendlyMessage = snapshot.getValue(FriendlyMessage::class.java)
            if (friendlyMessage != null) {
                friendlyMessage.id = snapshot.key!!
            }

            friendlyMessage!!
        }

        val messagesRef = mFirebaseDatabaseReference?.child(MESSAGES_CHILD);
        val options = messagesRef?.let {
            FirebaseRecyclerOptions.Builder<FriendlyMessage>()
                    .setQuery(it, parser)
                    .build()
        }

        mFirebaseAdapter = object : FirebaseRecyclerAdapter<FriendlyMessage, MessageViewHolder>(options!!) {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
                val inflater = LayoutInflater.from(parent.context)
                return MessageViewHolder(inflater.inflate(R.layout.item_message, parent, false))
            }

            override fun onBindViewHolder(holder: MessageViewHolder, position: Int, friendlyMessage: FriendlyMessage) {
                progressBar.visibility = View.INVISIBLE
                if (!friendlyMessage.text.isNullOrEmpty()) {
                    holder.messageTextView.text = friendlyMessage.text
                    holder.messageTextView.visibility = View.VISIBLE
                    holder.messageImageView.visibility = View.GONE
                } else if (!friendlyMessage.imageUrl.isNullOrEmpty()) {
                    val imageUrl = friendlyMessage.imageUrl
                    if (imageUrl.startsWith("gs://")) {
                        val storageReference = FirebaseStorage.getInstance()
                                .getReferenceFromUrl(imageUrl)

                        storageReference.downloadUrl.addOnCompleteListener {
                            if (it.isComplete) {
                                val downloadUrl = it.result.toString()
                                Glide.with(holder.messageImageView.context)
                                        .load(downloadUrl)
                                        .into(holder.messageImageView)
                            } else {
                                Log.w(TAG, "Getting download url was not successful.", it.exception)
                            }
                        }
                    } else {
                        Glide.with(holder.messageImageView.context)
                                .load(friendlyMessage.imageUrl)
                                .into(holder.messageImageView)
                    }

                    holder.messageImageView.visibility = View.VISIBLE
                    holder.messageTextView.visibility = View.GONE
                }

                holder.messengerTextView.text = friendlyMessage.name
                if (friendlyMessage.photoUrl == null) {
                    holder.messengerImageView.setImageDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_account_circle_black_36dp))
                } else {
                    Glide.with(this@MainActivity)
                            .load(friendlyMessage.photoUrl)
                            .into(holder.messengerImageView)
                }
            }
        }

        mFirebaseAdapter?.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                super.onItemRangeChanged(positionStart, itemCount)

                val friendlyMessageCount = mFirebaseAdapter?.itemCount
                val lastVisiblePosition = mLinearLayoutManager?.findLastCompletelyVisibleItemPosition()
                // If the recycler view is initially being loaded or the
                // user is at the bottom of the list, scroll to the bottom
                // of the list to show the newly added message.
                if (lastVisiblePosition == -1 || (positionStart >= (friendlyMessageCount!!.minus(1)) && lastVisiblePosition == (positionStart - 1))) {
                    messageRecyclerView.scrollToPosition(positionStart)
                }
            }
        })

        messageRecyclerView.adapter = mFirebaseAdapter

        messageEditText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(editable: Editable?) { }

            override fun beforeTextChanged(charSequence: CharSequence?, start: Int, count: Int, after: Int) { }

            override fun onTextChanged(charSequence: CharSequence?, start: Int, before: Int, count: Int) {
                sendButton.isEnabled = charSequence.toString().trim().isNotEmpty()
            }
        })

        sendButton.setOnClickListener {
            val friendlyMessage = FriendlyMessage(GoogleAuthProvider.PROVIDER_ID, messageEditText.text.toString(), mUsername!!, mPhotoUrl!!, "")
            mFirebaseDatabaseReference!!.child(MESSAGES_CHILD).push().setValue(friendlyMessage)

            messageEditText.text.clear()
        }

        addMessageImageView.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "image/*"
            startActivityForResult(intent, REQUEST_IMAGE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult: requestCode= $requestCode, resultCode= $resultCode");

        if (requestCode == REQUEST_IMAGE && resultCode == RESULT_OK && data != null) {
            val uri = data.data
            Log.d(TAG, "Uri: $uri")

            val tempMessage = FriendlyMessage(GoogleAuthProvider.PROVIDER_ID, "", mUsername!!, mPhotoUrl!!, LOADING_IMAGE_URL)
            mFirebaseDatabaseReference?.child(MESSAGES_CHILD)?.push()
                    ?.setValue(tempMessage) { databaseError, databaseReference ->
                        if (databaseError == null) {
                            val key = databaseReference.key
                            val storageReference = FirebaseStorage.getInstance()
                                    .getReference(mFirebaseUser!!.uid)
                                    .child(key!!)
                                    .child(uri!!.lastPathSegment!!)

                            putImageInStorage(storageReference, uri, key)

                        }
                    }
        }
    }

    private fun putImageInStorage(storageReference: StorageReference, uri: Uri, key: String) {
        storageReference.putFile(uri).addOnCompleteListener(this@MainActivity) {
            if (it.isSuccessful) {
                it.result?.metadata?.reference?.downloadUrl?.addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val friendlyMessage = FriendlyMessage("", "", mUsername!!, mPhotoUrl!!, task.result.toString())
                        mFirebaseDatabaseReference?.child(MESSAGES_CHILD)?.child(key)?.setValue(friendlyMessage)
                    }
                }
            } else {
                Log.w(TAG, "Image upload task was not successful.", it.exception)
            }
        }
    }

    public override fun onStart() {
        super.onStart()
        // Check if user is signed in.
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            val intent = Intent(this, SignInActivity::class.java)
            startActivity(intent)
        }
    }

    public override fun onPause() {
        mFirebaseAdapter?.stopListening()
        super.onPause()
    }

    public override fun onResume() {
        super.onResume()
        mFirebaseAdapter?.startListening()
    }

    public override fun onDestroy() {
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            R.id.sign_out_menu -> {
                mFirebaseAuth?.signOut()
                Auth.GoogleSignInApi.signOut(mGoogleApiClient)
                mUsername = ANONYMOUS
                startActivity(Intent(this, SignInActivity::class.java))
                finish()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onConnectionFailed(connectionResult: ConnectionResult) {
        Log.d(TAG, "onConnectionFailed:$connectionResult")
        Toast.makeText(this, "Google Play Services error.", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val TAG = "MainActivity"
    }
}

class MessageViewHolder(v: View) : RecyclerView.ViewHolder(v) {
    internal var messageTextView: TextView
    internal var messageImageView: ImageView
    internal var messengerTextView: TextView
    internal var messengerImageView: CircleImageView

    init {
        messageTextView = itemView.findViewById<View>(R.id.messageTextView) as TextView
        messageImageView = itemView.findViewById<View>(R.id.messageImageView) as ImageView
        messengerTextView = itemView.findViewById<View>(R.id.messengerTextView) as TextView
        messengerImageView = itemView.findViewById<View>(R.id.messengerImageView) as CircleImageView
    }
}