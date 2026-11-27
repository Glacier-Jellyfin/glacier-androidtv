# Jellyfin Enhanced: Seerr requests and Activity

Two features in Glacier need a helper on your server: requesting missing
titles through Seerr, and the Activity feed that shows what others watch and
favorite. Both come from the server plugin
[Jellyfin Enhanced](https://github.com/n00bcodr/Jellyfin-Enhanced). Glacier
talks only to your Jellyfin server and signs in with your normal Jellyfin
account. It never talks to Seerr itself, and no Seerr login or key is stored
on the TV.

Without the plugin, or with the features switched off, nothing breaks. The
Seerr results, the Activity page and the home row simply do not show.

## On the server

1. Install the Jellyfin Enhanced plugin from its project page and restart
   Jellyfin.
2. For requests: set up the Seerr connection in the plugin. The plugin holds
   the Seerr key, not Glacier.
3. For the Activity feed: use plugin version 12.3.0.0 or newer and switch on
   the Activity Feed in the plugin's settings.

Two plugin options decide who sees whose activity:

- "Hide reviews from hidden users": users the admin has hidden only appear in
  the feed when this is off.
- "ActivityFeedOwnOnlyForNonAdmins": when on, people who are not admins only
  see their own entries, so nobody else shows up for them. Switch it off if
  everyone should see the whole feed.

## Requests through Seerr

What you see: when you search in Glacier, the results from your library come
first. Titles that Seerr knows but your library does not have follow after
them. Only movies and shows are listed, and only those that are not in the
library already. Titles that are available or blocked in Seerr are left out.

How to request: open a Seerr result and choose "Request". For a show you pick
the seasons first. Seasons that were already requested cannot be asked for
again. Depending on the Seerr settings, a request has to be approved first.
After that the title shows up in your library by itself. If your Seerr account
has a request limit, Glacier shows "Requests left".

What it needs:

- The Jellyfin plugin is installed and connected to Seerr.
- Your Jellyfin user is linked to a Seerr user, for example by importing the
  Jellyfin users in Seerr. Glacier asks the plugin
  (`/JellyfinEnhanced/jellyseerr/user-status`) whether your user is active and
  known. A "no" is checked again after a while, so a user that the admin links
  later starts working without any change in Glacier.
- Your Seerr user may make requests. If not, Glacier says so and suggests
  asking the server admin.

This also works for accounts signed in with Quick Connect.

If anything fails, Glacier shows no Seerr results and does not show an error.
Requests that are refused say why: not allowed, no requests left for now, or
already requested.

## Activity

The feed shows what the people on your server watched and favorited. Glacier
uses it in these places.

- Home row "What others are watching". It can be moved or hidden in Settings ›
  Home, like the other rows. It only exists when the feed is available.
- The Activity page. It has the entry "Activity" in the top navigation, but
  only when the feed is available. The entry is on by default and can be
  hidden or moved in Settings › Appearance › Navigation. The page has the rows
  "Watched", "Favorited" and "Other users".
- A person's page, opened from "Other users". It shows "Watching now",
  "Watched" and "Favorites" for that person.
- Avatars on the cards of titles that others watched, and on the detail page a
  line saying who watched the title. The player info gets an "Activity" tab
  with one card per person.

Settings › Activity in Glacier:

- "Avatars on cards": shows who watched a title on its card.
- "Activity on the detail page": shows who watched a title on its page and in
  the player info.
- "Show activity from": pick whose activity you see. Switch off the people you
  do not want to see. Hidden people stay listed there, so you can switch them
  back on. Your own profile is never shown among the watchers.

Who sees what:

- Everything comes from the server, so what the plugin lets you see is the
  limit. See the two plugin options above. If the page is empty, Glacier
  explains that the admin has to turn on the Activity Feed and which of these
  options may hide entries.
- Age limits and parental controls of the Glacier profile apply: titles that
  the profile may not see are filtered out of the feed.

Limits:

- Glacier asks for at most 200 entries at once, which is the server maximum.
- The plugin keeps 500 entries in total for all users together and drops the
  oldest. The page and a person's page therefore show recent activity only.
- Glacier keeps the feed for 30 seconds, so a new entry can take a moment to
  appear.

## When something does not show

- No Seerr results in search: is the plugin installed and connected to Seerr,
  and is your Jellyfin user linked to a Seerr user? The title may also be in
  your library already, or available in Seerr.
- No "Activity" in the navigation and no home row: the plugin must be version
  12.3.0.0 or newer with the Activity Feed switched on. If the server answers
  that the feed does not exist (404), Glacier leaves the feature out.
- The Activity page is empty: check "Hide reviews from hidden users" and
  "ActivityFeedOwnOnlyForNonAdmins", and check Settings › Activity › "Show
  activity from". Nothing is shown until others have watched something.
- Entries are missing: the plugin keeps only the latest 500, and parental
  controls of your profile can filter titles out.
- Settings › Appearance › Navigation: make sure the Activity entry was not
  hidden.
