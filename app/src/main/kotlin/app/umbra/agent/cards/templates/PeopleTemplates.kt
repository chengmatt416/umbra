package app.umbra.agent.cards.templates

import app.umbra.agent.cards.*

/** These layouts arrange supplied facts; examples document the input contract for the agent. */
internal fun peopleTemplates(): List<CardTemplate> = listOf(
    template("People", "message_draft", "Draft", "message",
        "a message to review before requesting send or edits", "draft* to* channel* actions*",
        """{"title":"Message for Maya","draft":"Hi Maya, could we move our review to 14:00?","to":"Maya","channel":"Chat","actions":[{"id":"send","label":"Send draft","send":"Please send this draft to Maya."},{"id":"edit","label":"Edit draft","send":"I would like to edit this draft before sending it."}]}""",
    ) { add(CardBlock.Quote(required("draft"))); rows("to", "channel") },
    template("People", "email_summary", "Email", "message",
        "key points from an email", "from* subject* items*",
        """{"title":"Workshop invitation","from":"Maya Chen","subject":"Design workshop on Friday","items":[{"title":"Starts at 10:00"},{"title":"Bring your prototype"}]}""",
    ) { rows("from", "subject"); items() },
    template("People", "contact", "Contact", "user",
        "a person and known contact details", "person* details*",
        """{"title":"Maya Chen","person":{"name":"Maya Chen","role":"Product designer","detail":"Available Tuesday afternoon"},"details":{"Email":"maya@example.com","Office":"Taipei"}}""",
    ) { person(); kv() },
    template("People", "chat_digest", "Chats", "message",
        "chat summaries with message counts", "items*",
        """{"title":"Unread conversations","items":[{"title":"Design team","detail":"Review moved to Friday.","trailing":"6 messages"},{"title":"Book club","detail":"Next book selected.","trailing":"3 messages"}]}""",
    ) { items() },
    template("People", "call_notes", "Call", "phone",
        "call context and notes", "details* items*",
        """{"title":"Planning call","details":{"With":"Maya","When":"Oct 28, 10:00"},"items":[{"title":"Review the prototype","detail":"Collect feedback before Friday."},{"title":"Prepare the release","meta":"Alex · Friday"}]}""",
    ) { kv(); items() },
    template("People", "introduction", "Introduction", "users",
        "two people and why to connect", "first* second* text*",
        """{"title":"Maya, meet Alex","first":{"name":"Maya Chen","role":"Product designer","detail":"Available Tuesday afternoon"},"second":{"name":"Alex Rivera","role":"Android engineer"},"text":"You are both working on accessible navigation."}""",
    ) { person("first"); person("second"); lead() },
    template("People", "follow_up", "Follow-up", "bell",
        "a conversation requiring follow-up", "text* items*",
        """{"title":"Workshop follow-up","text":"Maya is waiting for your preferred time.","items":[{"title":"Choose a slot","meta":"Before Friday"}]}""",
    ) { callout(); items() },
    template("People", "thank_you", "Thank you", "heart",
        "a personal note of thanks", "text* person*",
        """{"title":"Thanks, Maya","text":"Your feedback made the prototype clearer and easier to use.","person":{"name":"Maya Chen","role":"Product designer","detail":"Available Tuesday afternoon"}}""",
    ) { lead(style = CardBlock.Text.TextStyle.SERIF); person() },
)
