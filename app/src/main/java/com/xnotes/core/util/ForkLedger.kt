package com.xnotes.core.util

import java.lang.ref.WeakReference

/** The forks an editor made, so a save still aimed at the file a document forked away from lands in its fork. */
class ForkLedger {

    private class Forward(val to: String, val owner: WeakReference<Any>)

    /** A fork's own name and the name its chain started from, both without the extension. */
    private class Named(val stem: String, val base: String)

    private val forwards = HashMap<String, Forward>()
    private val names = HashMap<String, Named>()

    /** Where [owner]'s save aimed at [uri] belongs: the newest fork [owner] made from it, else [uri] itself. */
    @Synchronized
    fun target(uri: String, owner: Any): String {
        var at = uri
        repeat(forwards.size) {
            val next = forwards[at]?.takeIf { it.owner.get() === owner } ?: return at
            at = next.to
        }
        return at
    }

    /** The name a fork of [uri] is numbered from: its chain's first name while [stem] is still the fork's own. */
    @Synchronized
    fun base(uri: String, stem: String): String = names[uri]?.takeIf { it.stem == stem }?.base ?: stem

    /** Record that [owner] forked [from] into the new file [to], named [stem] and numbered from [base]. */
    @Synchronized
    fun record(from: String, to: String, owner: Any, stem: String, base: String) {
        forwards.remove(to) // a uri reused by this new file must not still forward elsewhere
        forwards[from] = Forward(to, WeakReference(owner))
        names[to] = Named(stem, base)
    }
}
