# RC-N1C Flight Zone — V0

## Ziel
Ein kleines, flüssiges Android-Flugspiel für den DJI RC-N1C. Kein komplexer Simulator: kurze Sessions, minimalistisches 3D, gutes Stickgefühl.

## Vertical Slice
1. RC-N1C per USB erkennen und bestehende Reader-Schicht wiederverwenden.
2. Startscreen mit Controller-Status und „Fliegen“.
3. Eine leichte Three.js-Welt mit Himmel, Boden und stilisierten Low-Poly-Elementen.
4. Ruhige Flugphysik mit abgestimmten Roll/Pitch/Yaw-Kurven und etwas direkterem Throttle.
5. Drei große Tore in fester Reihenfolge.
6. Zielscreen mit Zeit, Toren und Präzision.

## Architektur
RC-N1C USB -> Input Adapter -> Stick Curves -> Flight Physics -> Three.js Scene -> Challenge State -> Result UI

Controller/USB bleibt von Spielwelt und Physik getrennt, damit spätere Modi dieselbe Eingabeschicht nutzen können.

## Nicht in V0
Accounts, Online-Rangliste, Multiplayer, fotorealistische Assets, mehrere Karten, In-App-Käufe.

## Performance-Ziel
Mobile-first; einfache Geometrie und Materialien; möglichst stabil 60 FPS auf dem Testgerät.
