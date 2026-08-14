#!/bin/bash
cvlc -I rc --rc-fake-tty "https://sample-videos.com/audio/mp3/crowd-cheering.mp3" < <(sleep 2; echo "quit")
