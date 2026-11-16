# Privacy

OpenTV collects nothing. There is no analytics SDK, no crash reporter, no telemetry, and no
network call to any domain the project controls — because the project controls none.

## What is stored, and where

Everything lives in a SQLite database in the app's private storage on your device:

- Your provider's address, username and password
- The channel, movie and series catalogue downloaded from your provider
- The guide (EPG)
- Your favourites, hidden channels and resume positions

None of it is uploaded anywhere. There is no account to create and no server to sync with.

## Who your device talks to

Only your provider, and only at the address you entered. Channel logos and posters are fetched
from whatever URLs your provider puts in its catalogue, which are usually its own servers but
may be third parties — that is your provider's choice, not ours.

[TMDB](https://www.themoviedb.org), for the posters, synopses and cast your provider left out, and
the IMDb match that add-ons need. With no setup at all, after every catalogue refresh Settee asks
TMDB about every film and show in your catalogue, by title and year (or the TMDB id your provider
gave), and asks again whenever you open a title's page. It sends the title being looked up and
Settee's own key, nothing about you or what you watch, though TMDB sees your device's IP address as
any website does. A build made without Settee's key, such as a debug build or a fork, never asks TMDB.

## Cleartext HTTP

The app allows plain HTTP (`usesCleartextTraffic="true"`). The overwhelming majority of IPTV
panels are HTTP-only; refusing them would make the app useless to most of the people it is for.

Be aware of what that means: **on an HTTP connection, your provider username and password are
sent unencrypted**, and anyone able to observe the network between you and your provider can
read them. This is a property of how these services are built, not of OpenTV. If your provider
offers an HTTPS endpoint, use it.

## Uninstalling

Removing the app removes the database and everything in it.

## Reporting a privacy problem

Open an issue. If it involves something you would rather not post publicly, say so in the issue
without the details and a maintainer will arrange somewhere better.
