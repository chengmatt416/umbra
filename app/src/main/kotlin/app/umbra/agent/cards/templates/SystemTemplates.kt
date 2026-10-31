package app.umbra.agent.cards.templates

import app.umbra.agent.cards.*

/** These layouts arrange supplied facts; examples document the input contract for the agent. */
internal fun systemTemplates(): List<CardTemplate> = listOf(
    template("System", "confirm", "Confirm", "hand",
        "a concrete action needing the user’s confirmation", "text* actions*",
        """{"title":"Move the meeting","text":"Move the design review from 10:00 to 14:00 on Friday?","actions":[{"id":"confirm","label":"Confirm move","send":"Yes, move the design review to 14:00 on Friday."},{"id":"cancel","label":"Cancel move","send":"Keep the design review at 10:00 on Friday."}]}""",
    ) { callout() },
    template("System", "options", "Options", "fork",
        "options to choose, with supplied recommendation badges", "options* question* submitLabel*",
        """{"title":"Choose a workshop format","question":"Which format works best?","options":[{"id":"short","label":"30-minute review","detail":"One prototype","badge":"Recommended","recommended":true},{"id":"long","label":"60-minute workshop","detail":"Two prototypes"}],"submitLabel":"Choose format"}""",
    ) { choice("poll", false) },
    template("System", "question", "Question", "message",
        "collecting the fields needed to continue", "fields* submitLabel*",
        """{"title":"Plan your visit","fields":[{"id":"date","label":"Visit date","kind":"DATE","required":true},{"id":"guests","label":"Guests","kind":"NUMBER","required":true}],"submitLabel":"Share details"}""",
    ) { val fields = objects("fields").map {
            CardBlock.Form.Field(it.required("id", 80), it.required("label", 80), it.enum("kind", CardBlock.Form.Kind.TEXT),
                it.strings("options"), it.text("placeholder"), it.text("value"), it.bool("required"))
        }
        unique(fields.map { it.id }, "fields"); add(CardBlock.Form("form", fields, required("submitLabel", 80))) },
    template("System", "status_update", "Status", "workflow",
        "what was done and what remains", "stats* steps*",
        """{"title":"Research update","stats":[{"label":"Sources reviewed","value":"8"},{"label":"Open questions","value":"2"}],"steps":[{"title":"Review sources","state":"DONE"},{"title":"Compare findings","state":"CURRENT"},{"title":"Resolve open questions","state":"TODO"}]}""",
    ) { stats(); steps() },
    template("System", "file", "File", "file",
        "file metadata and a short description", "name* size* file_type* modified* text*",
        """{"title":"Workshop notes","name":"workshop-notes.md","size":"12 KB","file_type":"Markdown","modified":"Oct 28, 10:30","text":"Decisions and action items from the design workshop."}""",
    ) { rows("name", "size", "file_type", "modified"); lead() },
)
