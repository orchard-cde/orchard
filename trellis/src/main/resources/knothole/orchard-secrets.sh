# Installed by Orchard. Exports the grove's injected secrets into login and interactive shells.
for _orchard_f in /dev/shm/orchard/secrets.env /run/orchard/secrets/secrets.env; do
  if [ -r "$_orchard_f" ]; then
    case $- in *a*) _orchard_a=1 ;; *) _orchard_a=0 ;; esac
    set -a
    . "$_orchard_f"
    if [ "$_orchard_a" = 0 ]; then set +a; fi
    break
  fi
done
unset _orchard_f _orchard_a
