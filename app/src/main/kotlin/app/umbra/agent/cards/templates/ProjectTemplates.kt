package app.umbra.agent.cards.templates

import app.umbra.agent.cards.*

/** These layouts arrange supplied facts; examples document the input contract for the agent. */
internal fun projectTemplates(): List<CardTemplate> = listOf(
    template("Projects", "project_status", "Project", "workflow",
        "project completion and next steps", "value* stats* steps*",
        """{"title":"Website refresh","value":0.65,"stats":[{"label":"Tasks done","value":"13"},{"label":"Tasks total","value":"20"}],"steps":[{"title":"Research","state":"DONE"},{"title":"Implement screens","state":"CURRENT"},{"title":"Review accessibility","state":"TODO"}]}""",
    ) { progress(); stats(); steps() },
    template("Projects", "goal_progress", "Goal", "target",
        "progress toward a goal", "value* steps*",
        """{"title":"Read twelve books","value":0.5,"label":"6 of 12 read","steps":[{"title":"Read the first six books","state":"DONE"},{"title":"Read books seven to nine","state":"CURRENT"},{"title":"Complete the final three","state":"TODO"}]}""",
    ) { progress(); steps() },
    template("Projects", "task_progress", "Task", "checks",
        "the current state of a task sequence", "steps*",
        """{"title":"Prepare the demo","steps":[{"title":"Prepare","state":"DONE"},{"title":"Review","state":"CURRENT"},{"title":"Finish","state":"TODO"}]}""",
    ) { steps() },
    template("Projects", "milestone", "Milestone", "target",
        "one milestone reached or approaching", "figure* text* chips*",
        """{"title":"First public release","figure":"1,000","unit":"downloads","text":"The first milestone was reached today.","chips":["Public beta","Oct 28"],"tone":"GOOD"}""",
    ) { figure(); lead(); chips() },
    template("Projects", "sprint_board", "Sprint", "grid",
        "sprint counts and highlighted work", "todo* doing* done* items*",
        """{"title":"Sprint 14","todo":5,"doing":3,"done":12,"items":[{"title":"Review the prototype","detail":"Collect feedback before Friday."},{"title":"Prepare the release","meta":"Alex · Friday"}]}""",
    ) { countStats("todo", "doing", "done"); items() },
    template("Projects", "risk_register", "Risks", "shield",
        "risk likelihood and impact", "columns* rows* severity*",
        """{"title":"Release risks","columns":["Risk","Likelihood","Impact"],"rows":[["API delay","Medium","High"],["Missing screenshots","Low","Low"]],"severity":"WARN"}""",
    ) {
        table()
        cardTone = enum("severity", CardTone.NEUTRAL)
        if (cardTone !in setOf(CardTone.NEUTRAL, CardTone.WARN, CardTone.DANGER)) error("severity", "must be NEUTRAL, WARN or DANGER")
    },
    template("Projects", "roadmap", "Roadmap", "workflow",
        "phases over time", "events*",
        """{"title":"Autumn release","events":[{"time":"Nov","title":"Private beta"},{"time":"Dec","title":"Public release"}]}""",
    ) { timeline() },
    template("Projects", "okr", "OKR", "target",
        "objectives and measured key results", "items* bars* unit*",
        """{"title":"Improve onboarding","items":[{"title":"Make the first session useful","detail":"Reduce setup friction."}],"bars":[{"label":"Activation","value":70},{"label":"Tutorial completion","value":82}],"unit":"%"}""",
    ) { items(); bars() },
    template("Projects", "weekly_review", "Weekly review", "history",
        "weekly wins and misses", "stats* wins* misses*",
        """{"title":"Week 44 review","stats":[{"label":"Tasks done","value":"12"},{"label":"Focus time","value":"20","unit":"h"}],"wins":[{"title":"Prototype tested"}],"misses":[{"title":"Docs still need review"}]}""",
    ) { stats(); items("wins", fixedTone = CardTone.GOOD, fixedIcon = "check"); items("misses", fixedTone = CardTone.WARN, fixedIcon = "alert") },
    template("Projects", "time_tracking", "Time", "clock",
        "hours spent per project", "bars* stats*",
        """{"title":"Time this week","bars":[{"label":"Research","value":12},{"label":"Design","value":8}],"stats":[{"label":"Total","value":"20","unit":"h"},{"label":"Projects","value":"2"}]}""",
    ) { bars(unit = text("unit", 80) ?: "h"); stats() },
)
