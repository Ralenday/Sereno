#!/usr/bin/env bash
# Regenera las copias de OpenStreetMap que trae la app en mobile/src/main/assets/.
# Usa las MISMAS cajas y filtros que Osm.consultaLineas() / Osm.consultaHoteles(); si cambias uno,
# cambia el otro. Overpass a veces responde 502/504: el script prueba 3 servidores.
# Uso: herramientas/actualizar_datos.sh   (desde la raíz del proyecto)
set -euo pipefail
cd "$(dirname "$0")/../mobile/src/main/assets"

SERVIDORES=(https://overpass-api.de/api/interpreter https://overpass.private.coffee/api/interpreter https://overpass.kumi.systems/api/interpreter)
AGENTE="Sereno/0.1 (proyecto escolar)"

consultar() { # salida consulta
  for s in "${SERVIDORES[@]}"; do
    if curl -sf -m 180 -A "$AGENTE" --data-urlencode "data=$2" "$s" -o "$1.tmp" &&
       python3 -I -c "import json,sys; d=json.load(open(sys.argv[1])); assert d['elements']" "$1.tmp"; then
      mv "$1.tmp" "$1"; echo "OK $1 ($s)"; return 0
    fi
    echo "falló $s, reintentando..."; sleep 20
  done
  rm -f "$1.tmp"; echo "NO SE PUDO: $1"; return 1
}

caja() { python3 -c "print(f'{$1-$3},{$2-$3},{$1+$3},{$2+$3}')"; }

FIJAS="subway|light_rail|tram|monorail|train|aerialway"
TURISMO='nwr["tourism"~"^(hotel|motel|hostel|guest_house|apartment)$"]'

# id lat lon medio_lado
for c in "slrc 32.4840 -114.7790 0.135" "cdmx 19.4326 -99.1332 0.25"; do
  set -- $c
  B=$(caja "$2" "$3" "$4")
  consultar "hoteles_$1.json" "[out:json][timeout:170];$TURISMO($B);out center tags;" || true
  if [ "$1" = cdmx ]; then
    Q="[out:json][timeout:170];(rel[type=route][route~\"^($FIJAS)\$\"]($B);rel[type=route][route=bus][network~\"Metrob\",i](19.18,-99.38,19.68,-98.88);rel[type=route][route=trolleybus](19.18,-99.38,19.68,-98.88);)->.r;.r out body;node(r.r); out body;"
  else
    Q="[out:json][timeout:170];rel[type=route][route~\"^($FIJAS|bus|trolleybus|minibus|share_taxi)\$\"]($B)->.r;.r out body;node(r.r); out body;"
  fi
  consultar "lineas_$1.json" "$Q" || true
done
ls -la
