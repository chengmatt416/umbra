package app.umbra.agent.cards.templates

import app.umbra.agent.cards.*

/** These layouts arrange supplied facts; examples document the input contract for the agent. */
internal fun homeTemplates(): List<CardTemplate> = listOf(
    template("Home", "workout", "Workout", "heart",
        "a supplied exercise plan", "stats* checks* sendLabel*",
        """{"title":"Tuesday workout","stats":[{"label":"Duration","value":"30","unit":"min"},{"label":"Exercises","value":"2"}],"checks":[{"id":"walk","text":"Warm-up walk","detail":"5 minutes"},{"id":"squat","text":"Squats","detail":"3 sets of 8, from your plan"}],"sendLabel":"Log workout"}""",
    ) { stats(); checks() },
    template("Home", "nutrition", "Nutrition", "heart",
        "nutrition facts supplied by the user", "details* slices* unit*",
        """{"title":"Lunch nutrition","details":{"Energy":"520 kcal","Serving":"1 bowl"},"slices":[{"label":"Carbohydrate","value":60},{"label":"Protein","value":30},{"label":"Fat","value":18}],"unit":"g"}""",
    ) { kv(); donut() },
    template("Home", "sleep", "Sleep", "moon",
        "a sleep history and summary", "stats* points* unit*",
        """{"title":"Sleep this week","stats":[{"label":"Average","value":"7.2","unit":"h"},{"label":"Nights","value":"7"}],"points":[{"label":"Mon","value":7},{"label":"Tue","value":7.5},{"label":"Wed","value":6.5},{"label":"Thu","value":7},{"label":"Fri","value":8},{"label":"Sat","value":7},{"label":"Sun","value":7.5}],"unit":"h"}""",
    ) { stats(); columns() },
    template("Home", "recipe", "Recipe", "home",
        "a recipe with ingredients and method", "details* checks* steps* sendLabel*",
        """{"title":"Overnight oats","details":{"Prep":"5 minutes","Servings":"1"},"checks":[{"id":"oats","text":"Rolled oats","quantity":"50 g"},{"id":"milk","text":"Milk","quantity":"120 ml"}],"steps":[{"title":"Mix oats and milk"},{"title":"Refrigerate overnight"}],"sendLabel":"Update ingredients"}""",
    ) { kv(); checks(); steps() },
    template("Home", "medication", "Medication", "clock",
        "only a medication schedule the user already supplied", "events*",
        """{"title":"My medication schedule","events":[{"time":"08:00","title":"Morning reminder","detail":"Follow my saved prescription instructions."},{"time":"20:00","title":"Evening reminder","detail":"As recorded in my schedule."}],"source":"User-provided schedule"}""",
    ) { timeline() },
    template("Home", "order_tracking", "Order", "box",
        "order stages and order details", "steps* details*",
        """{"title":"Book order","steps":[{"title":"Order confirmed","state":"DONE"},{"title":"Packed","state":"CURRENT"},{"title":"Dispatched","state":"TODO"},{"title":"Delivered","state":"TODO"}],"details":{"Carrier":"City delivery","Ordered":"Oct 26"}}""",
    ) { steps(); kv() },
    template("Home", "package_delivery", "Delivery", "box",
        "a package ETA and delivery stages", "figure* steps*",
        """{"title":"Books arriving today","figure":"14:00–16:00","steps":[{"title":"Dispatched","state":"DONE"},{"title":"Out for delivery","state":"CURRENT"},{"title":"Delivered","state":"TODO"}],"caption":"Expected today"}""",
    ) { figure(); steps() },
)
