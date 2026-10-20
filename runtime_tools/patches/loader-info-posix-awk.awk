# AndChamber build tool: POSIX-awk replacement for upstream proot/src/loader/loader-info.awk.
#
# Upstream uses gawk-only strtonum(), which mawk (the default awk on many Linux hosts)
# does not provide. This version parses hexadecimal symbol values with a small
# hex-to-decimal routine. Output is identical: the same C constant
# offset_to_pokedata_workaround = (pokedata_workaround - start).
#
# Input: `readelf -s <loader>` output. Used only by the PRoot build's HAS_POKEDATA_WORKAROUND path.

function hex2dec(text,    i, c, value, digit) {
	value = 0
	sub(/^0[xX]/, "", text)
	for (i = 1; i <= length(text); i++) {
		c = substr(text, i, 1)
		digit = index("0123456789abcdef", tolower(c)) - 1
		if (digit < 0) {
			return value
		}
		value = value * 16 + digit
	}
	return value
}

# readelf -s fields: Num: Value Size Type Bind Vis Ndx Name
$NF == "pokedata_workaround" {
	pokedata_workaround = hex2dec($2)
}

$NF == "_start" {
	start = hex2dec($2)
}

END {
	print "#include <unistd.h>"
	print "const ssize_t offset_to_pokedata_workaround=" (pokedata_workaround - start) ";"
}
