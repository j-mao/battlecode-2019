#!/bin/sh

if [ $# -ne 1 ]; then
	echo "Usage: $0 compiled_bot.js      where compiled_bot.js is the bot you wish to upload."
	exit 1
fi

printf "Battlecode 2019 Username: "
read username

stty -echo
printf "Battlecode 2019 Password: "
read password
stty echo
echo

export BC_USERNAME=$username
export BC_PASSWORD=$password

bc19upload -i $1
