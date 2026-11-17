# Contributing to xnotes

Thank you for taking the time to improve xnotes.

The rules below apply to every pull request. A poorly prepared pull request can take longer to review than the change would take to write from scratch, so pull requests that do not follow these rules may be closed without review.

## AI-generated code

AI tools may be used to write code. Unreviewed AI output is not accepted.

- The contributor must understand every line of the pull request and be able to explain any of it on request.
- A small number of well-understood pull requests is far more useful than many unreviewed ones, each of which the maintainer would have to review alone.
- Every pull request description must include an **AI usage** section naming the models and the environment used (for example, "Claude Opus 5.5 in Claude Code" or "Gemini in Cursor"), or stating that none were used.

## Testing

Every pull request must state the device model, Android version and stylus the change was tested on.

## Screenshots and recordings

Screenshots and screen recordings are not mandatory, but they are expected wherever they make a change easier to review:

- A screenshot, wherever one shows the change faster than a description.
- A screen recording, wherever motion or interaction matters.
- Before and after recordings, wherever the change alters existing behaviour.

## Bug fixes and UI changes

Bug fixes are always welcome.

The maintainer is particular about the app's design. Pull requests that significantly change the UI are welcome, but they are merged only if the maintainer is satisfied with the design, and they may remain unreviewed for a long time.

## Commits

Commit messages follow the style of the existing history:

- The title is a plain imperative sentence, with no type prefix such as `feat(toolbar):` and no trailing period.
- Lines are wrapped at 72 columns.
- The body explains what was wrong and why the change fixes it.
- AI attribution goes in the pull request description only. Commits must not carry trailers such as `Generated with ...` or an AI `Co-Authored-By`.

For example:

```
Reopen the wet pad layer after a rotation

The pad's buffer is turned to the display once, when its layer and
surface are made. After a rotation it stayed turned the old way, so
panels that cannot turn a layer themselves copied it every refresh
and the ink lagged or waited for pen up.
```

## Review and credit

Review is not guaranteed. Any pull request may remain unreviewed or be closed without detailed feedback; this is not a judgement of the effort put into it.

The maintainer may push changes to a pull request, squash it, or reimplement the change and close the pull request. Credit is recorded in the commit history only:

- If most of the contributed code is kept, its commits keep the contributor as the author.
- If the change is rewritten, the contributor is credited with a `Co-authored-by` trailer on the commit.

## License

By submitting a pull request, you agree that your contribution is licensed under the [MIT License](LICENSE).
