#!/bin/sh

# To use this submit script, write your username and
# password into the files bc_username and bc_password
# Then just run submit.sh file.js
# where file.js is the compiled javascript you want to
# submit

if [ $# -ne 1 ]; then
	echo "Usage: $0 file.js"
	exit 1
fi

export BC_USERNAME=`cat bc_username`
export BC_PASSWORD=`cat bc_password`
bc19upload -i $1
