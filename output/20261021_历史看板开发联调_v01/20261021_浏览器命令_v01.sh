#!/bin/bash
export XDG_CACHE_HOME=/tmp/cdn-browser-acceptance/cache
export PLAYWRIGHT_BROWSERS_PATH=/tmp/cdn-browser-acceptance/browsers
exec /tmp/cdn-browser-acceptance/node_modules/.bin/playwright-cli -s=cdn-history "$@"
