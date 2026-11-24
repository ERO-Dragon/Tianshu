package com.rheinmetal.tianshu.dubhe.agent.skill.book;

/**
 * One readable entry of a mod's in-game guide.
 *
 * <p>{@code text} is already resolved to plain readable prose: the translation key has been looked up, the
 * Patchouli format controls have been stripped, and only the text pages are joined. A caller never has to know
 * which mod shipped the book or how it was laid out.</p>
 *
 * @param modId  the mod the entry came from, as the player sees it in their mod list
 * @param title  the entry title in the book's language
 * @param text   the entry body, paragraphs separated by a blank line
 */
public record DubheBookEntry(String modId, String title, String text) {
}