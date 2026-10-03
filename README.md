# Aces & Eclipses Companion App

The companion app for the *Aces & Eclipses Core Rulebook*, the fantasy roleplaying game played with cards instead of dice.

- **Build** a balanced star-sign deck, step by step, with the Lab test.
- **Play** it on your phone in place of paper cards: checks, fortune and misfortune, rerolls, spells, held draws, sign abilities, deck-outs and exhaustion. Enter your character's attributes and the app fills in your attack, Perception and save modifiers. A mis-tapped check can be undone: its cards go back and the rest of the deck is reshuffled, so nobody sees their next card.
- **Run the game** as GM: the Fate Deck (100 cards, or 112 with the face cards when the table plays with wild magic), flat checks, wild magic with all 93 combinations (an optional rule, in Aces & Eclipses and in d20 Classic, switched on or off on the Fate Deck page), and the damage deck. The GM's Draw panel has the same Undo.
- **Read** the whole *Aces & Eclipses Core Rulebook*, searchable.

It works with no internet once it's on your phone. A bonus mode, **d20 Classic compatibility** (off by default, under Settings), plays the same decks with fifth edition and 3.5 rules.

## Get it

- **Any phone or computer:** open **https://estuswick.github.io/star-sign-table/**, then install it: on Android, Chrome's menu → *Install app*; on iPhone, Safari's Share button → *Add to Home Screen*.
- **Android app file:** [StarSignTable.apk](https://github.com/Estuswick/star-sign-table/raw/apk/StarSignTable.apk). Your phone will ask you to allow installs from your browser or files app. New versions install over the old one and keep your decks.

This app used to be called the Star-Sign Table. Decks saved in it carry over on their own.

## What's in here

- `docs/`: the app itself (one self-contained page), served as the website.
- `android/`: a small Android app that runs the same page with no internet.
- `tools/book_data.py`: puts a new version of the Core Rulebook (its Markdown) into the app: the Rules tab and the wild magic table. Give it *Core Engine: d20 Classic* as well to update d20 Classic's wild magic names, effects and Oddities.
- `.github/workflows/android.yml`: builds the Android app automatically whenever the app changes.
- `LICENSES.md`: the license notices.

## Licenses

*Aces & Eclipses Companion App* © 2026 Different Games. Aces & Eclipses and the Aces & Eclipses Compatibility Logo are trademarks of Different Games. The Aces & Eclipses rules are licensed under the ORC License; the d20 Classic rules include material from the System Reference Document 5.2.1 by Wizards of the Coast LLC, licensed under the Creative Commons Attribution 4.0 International License. Compatible with fifth edition. The fonts are under the SIL Open Font License 1.1, and the Android app includes AndroidX WebKit under the Apache License 2.0. The full notices are in [LICENSES.md](LICENSES.md) and in the app, under Settings → Licenses.
