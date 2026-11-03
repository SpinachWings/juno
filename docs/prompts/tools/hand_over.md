# hand_over

Gives your work to another agent you are linked to, when you can't or shouldn't do it yourself (a different branch,
a different part of the code, you're out of context). Only a linked agent that edits files and is idle can take it;
otherwise nothing is sent and the result says why. The task arrives as a message from you. It doesn't wait: the
other agent sends you its result as a message when it's done.

## Parameters
- `to`: the agent's name (case and spaces are ignored).
- `task`: the task, self-contained: goal, facts, the branch and folder involved, what done looks like, what is ruled
  out. The other agent sees only this.
