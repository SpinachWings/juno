# Results: assign_task

## delivered
Delivered to {{to}} ({{#fresh}}new conversation{{/fresh}}{{^fresh}}continuing its conversation{{/fresh}}){{#queued}}.
It's queued, {{queued}}{{/queued}}. Its result will be reported to you.

## undo-window
It goes to {{to}} in {{seconds}} seconds, continuing its conversation, unless the user undoes it. If they do, a message tells you. Otherwise its result will be reported to you.

## held
Held for the user's approval ({{why}}). The outcome arrives as a message.

## not-assignable
{{to}} is Juno. Do the routing yourself; you can't assign tasks to yourself.

## why-unnamed
the user didn't name {{to}}

## why-always
the user approves every assignment

## why-fresh
a fresh start always needs the user's approval

## place
{{place}} in line for a free slot

## after-turn
after its current turn
