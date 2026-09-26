# GabCon DH Sync

Mod **NeoForge 1.21.1 / Java 21** pour distribuer les données **Distant Horizons** d'un serveur Minecraft via **GitHub Releases**, afin d'éviter que le serveur domestique n'envoie directement plusieurs gigaoctets de LOD à chaque client.

> **État : 0.7.0-rc3 en validation réelle.** La base 0.6.2 reste la version stable validée sur GabCon. La RC 0.7 ajoute l'auto-publication serveur crash-safe, une matrice CI de crash/recovery client et la validation SHA-256 distante des assets GitHub. Les blockers de revue ont été corrigés : status non bloquant pendant les uploads, réparation SHA non destructive pour les assets encore référencés, récupération sûre du manifest distant si la copie locale manque, conservation de l'âge du dirty marker entre redémarrages, et isolation/cancellation des tâches auto-publish entre cycles de serveur intégré. La CI d'intégration est verte ; il reste la validation réelle serveur/client avant merge vers `main`.

## Cible

- Minecraft `1.21.1`
- NeoForge `21.1.251`
- Java `21`
- Distant Horizons `3.3.2` / API `7.2.0`
- paire legacy encore acceptée : DH `3.3.1` / API `7.1.0`
- `worldId = gabcon-main`
- même JAR GabCon côté serveur et client

DH n'est pas embarqué dans GabCon.

## Sécurité SQLite

GabCon ne modifie jamais la DB DH active.

Côté serveur, `/gabcondhsync snapshot` utilise l'API publique DH pour passer temporairement le monde en lecture seule, récupère les dossiers via `IDhApiLevelWrapper.getDhSaveFolder()`, puis utilise le backup SQLite en ligne du pilote `dh_sqlite`. Chaque copie passe `PRAGMA quick_check`.

Côté client, aucune écriture n'est faite pendant qu'une DB DH est active ; les sidecars actifs `-wal/-shm` provoquent un refus.

Pour un **bootstrap** (ou si `compactIncrementalApply=false`), GabCon conserve le modèle historique : préparation dans un fichier `.gabcon-work`, vérification, remplacement atomique et rollback complet.

Pour une mise à jour **delta-only** avec `compactIncrementalApply=true`, GabCon ne recopie plus toute la DB. Avant chaque delta, il crée un reverse-delta compact contenant uniquement les anciennes lignes touchées et les clés des nouvelles lignes. Ce rollback est journalisé avant la transaction SQLite. Si un crash survient avant le commit de l'état client, les reverse-deltas sont rejoués en ordre inverse au prochain démarrage. Cette restauration est idempotente, donc elle reste sûre même si le crash a eu lieu juste avant ou juste après le commit SQLite.

## Commandes serveur

```text
/gabcondhsync status
/gabcondhsync snapshot
/gabcondhsync delta
/gabcondhsync publish
/gabcondhsync reload
```

### snapshot

Crée sous :

```text
gabcondhsync/snapshots/<worldId>/<timestamp>/
```

une DB SQLite cohérente par dimension et un `snapshot.json`.

### delta

Compare les deux snapshots les plus récents. Les tables `FullData`, `ChunkHash` et `BeaconBeam` sont comparées par clé primaire. Le delta contient :

- `<Table>Upsert` : lignes ajoutées/modifiées ;
- `<Table>Delete` : clés supprimées.

Les BLOB DH sont copiés octet pour octet. `Schema` doit être identique et `Legacy_FullData_V1` vide.

Même un delta de **0 opération** est conservé si les hashes physiques des snapshots diffèrent : ce petit delta fait avancer la baseline logique et évite de casser la chaîne suivante.

### publish

Publie vers une Release stable, par défaut :

```text
gabcon-data-gabcon-main
```

Le token GitHub est lu **uniquement** depuis :

```text
GABCON_DH_GITHUB_TOKEN
```

Il ne doit jamais être écrit dans le dépôt, le JAR, une config client ou un chat.

La première publication choisit le snapshot valide le plus ancien comme bootstrap. Les DB sont découpées par défaut en morceaux de **1 GiB**, chaque morceau reçoit taille + SHA-256, puis les deltas sont ajoutés dans l'ordre. `manifest.json` est uploadé **en dernier**, donc un client ne peut pas voir un manifest référençant des assets encore incomplets.

Les noms de bootstrap/deltas sont immuables et liés aux baselines. En 0.7, un asset distant n'est réutilisé que si **nom + taille + digest SHA-256 GitHub** correspondent. Un asset absent, de mauvais digest ou issu d'une ancienne Release sans digest est reconstruit depuis les snapshots/deltas locaux puis ré-uploadé. `manifest.json` n'est remplacé qu'après un preflight SHA-256 de tous les assets qu'il référence.

Les publications suivantes uploadent uniquement les nouveaux deltas puis remplacent `manifest.json`.

## Manifest de distribution 0.5

Le schéma de distribution actif est `2`. Il contient :

- versions MC / NeoForge / DH / API ;
- `worldId` ;
- tag de Release ;
- pour chaque dimension :
  - bootstrap complet, éventuellement découpé en plusieurs assets ;
  - chaîne ordonnée de deltas `oldServerBaselineSha256 -> newServerBaselineSha256`.

Validation stricte : HTTPS, noms sûrs, tailles bornées, SHA-256, noms d'assets globalement uniques et continuité complète de la chaîne.

Le SHA de baseline est un **token de chaîne serveur**. Après application SQLite, le fichier client peut être logiquement identique tout en ayant un SHA physique différent.

## Client 0.5 : enregistrement unique

Pour éviter de deviner les chemins internes de DH, la première version 0.5 demande un enregistrement une seule fois.

Avec le JAR 0.5 installé, connecte-toi normalement à GabCon, puis exécute :

```text
/gabcondhsyncclient register
```

GabCon récupère :

- l'adresse réelle du serveur ;
- `worldId=gabcon-main` ;
- l'URL du manifest ;
- les chemins exacts des DB DH actuellement chargées via l'API publique DH.

Commandes disponibles :

```text
/gabcondhsyncclient register
/gabcondhsyncclient status
/gabcondhsyncclient forget
```

Relancer `register` plus tard fusionne les nouvelles dimensions et **préserve les baselines existantes**.

L'état est écrit atomiquement dans :

```text
<gameDir>/gabcondhsync/client-state.json
```

## Client 0.5 : pré-connexion

Pour un serveur non enregistré, GabCon ne change rien.

Pour un serveur enregistré, l'appel à `ConnectScreen.startConnecting` est intercepté **avant l'ouverture réseau** :

1. récupération HTTPS du `manifest.json` ;
2. validation `worldId`, Minecraft, NeoForge, DH/API ;
3. calcul du plan :
   - déjà à jour → connexion immédiate ;
   - baseline connue → deltas manquants uniquement ;
   - baseline inconnue → rebootstrap sûr ;
4. téléchargement des assets avec `.part`, HTTP Range, retries, limite de taille, SHA-256, progression et limite de débit optionnelle ;
5. vérification DB inactive et espace disque ;
6. création/assemblage des fichiers `.gabcon-work` ;
7. application transactionnelle de la chaîne de deltas ;
8. `quick_check` de toutes les DB préparées ;
9. écriture du journal de récupération ;
10. renommage des DB originales en rollback ;
11. installation des DB préparées ;
12. `quick_check` final ;
13. mise à jour atomique du fichier d'état ;
14. connexion Minecraft.

En cas d'échec avant commit, les DB originales restent intactes. En cas d'échec pendant le commit, toutes les dimensions déjà remplacées sont restaurées. En cas de crash machine/Java, le journal est traité au prochain démarrage client.

Si `allowFallback=true` et qu'aucune récupération critique n'est en attente, un échec de synchronisation peut retomber sur la connexion/DH native.

## Configuration serveur

- `enabled=true`
- `repository=misterblaecher/GabCon-DH-Sync`
- `worldId=gabcon-main`
- `releaseTag=gabcon-data-gabcon-main`
- `publishIntervalMinutes=30`
- `changedRegionThreshold=32`
- `nativeDhFallbackEnabled=true`
- `autoPublish=false`
- `maxDownloadBytes=2147483648`
- `bootstrapPartBytes=1073741824`

`autoPublish=false` reste la valeur par défaut pendant la RC 0.7. Quand il est activé, le serveur déclenche automatiquement `snapshot → delta → publish` dès que `changedRegionThreshold` est atteint, ou quand le plus ancien changement pending dépasse `publishIntervalMinutes`. Les changements sont acquittés uniquement après publication et vérification du manifest distant ; un échec conserve le cycle et les changements pending pour reprise.

## Fiabilité 0.7.0

L'auto-publication utilise un watermark atomique des régions modifiées et un état persistant par phases. Une sauvegarde de chunk arrivée pendant une publication reste pending pour le cycle suivant. Un marqueur `dirty` persiste avant l'état mémoire afin qu'un arrêt ou crash serveur ne puisse pas oublier des changements encore non publiés. Au démarrage, un cycle interrompu reprend sa phase au lieu de remplacer silencieusement son snapshot.

Les chemins de commit client sont couverts par une matrice de fault injection : `PREPARED`, installation partielle des DB, `TARGETS_INSTALLED`, état client écrit avant `STATE_COMMITTED`, `STATE_COMMITTED`, recovery multi-dimension, journal corrompu et reverse-delta manquant. Le recovery incrémental rejoue de vrais reverse-deltas SQLite et les tests vérifient l'idempotence.

Côté GitHub Release, le producteur lit le champ `digest` de chaque asset, valide le digest retourné après upload, pagine la liste complète des assets et répare les assets historiques non vérifiables avant le preflight final.

## Configuration client

- `enabled=true`
- `autoDownload=true`
- `connectOnComplete=true`
- `allowFallback=true`
- `interceptManagedConnections=true`
- `maxConcurrentDownloads=1`
- `optionalDownloadSpeedLimit=0` (0 = illimité)
- `maxDownloadBytes=2147483648`
- `worldId=gabcon-main`
- `repository=misterblaecher/GabCon-DH-Sync`
- `releaseTag=gabcon-data-gabcon-main`
- `manifestUrlOverride=""`

## Résultats réels GabCon déjà validés

Premier snapshot DH 3.3.2 :

- Overworld : `10,406,621,184` octets ;
- Nether : `61,440` octets ;
- End : `61,440` octets.

Deuxième snapshot : même taille Overworld, SHA physique différent.

Delta réel :

- taille : `35,909,632` octets (~34,3 MiB) ;
- SHA-256 : `d2d48447d518998ccfc9b4991cfdea4298c58a52c68e1baae0d38785ba9ddd24` ;
- `FullData` : 938 upserts ;
- `ChunkHash` : 3 702 upserts ;
- suppressions : 0 ;
- total : **4 640 opérations** ;
- `quick_check=ok`.

Round-trip réel :

```text
snapshot 1 + delta == snapshot 2
```

au niveau logique exact :

- `FullData` : 128 028 lignes, diff bidirectionnelle 0 ;
- `ChunkHash` : 1 280 354 lignes, diff 0 ;
- `BeaconBeam` : diff 0 ;
- `Schema` : diff 0 ;
- `Legacy_FullData_V1` : diff 0 ;
- `quick_check=ok` avant/après.

Cela valide le format différentiel sur les vraies données GabCon. Le delta représente environ **0,35 %** du snapshot Overworld complet.

## Validation réelle client : enregistrement

Le premier test client 0.5 a validé l'enregistrement sur le vrai serveur GabCon :

- serveur enregistré : `[fe80::79eba6b9cf7b2d]:25565` ;
- `worldId=gabcon-main` ;
- 1 DB DH actuellement découverte ;
- `baselines=0`, attendu avant le premier bootstrap GabCon ;
- URL du manifest : Release `gabcon-data-gabcon-main/manifest.json`.

La première reconnexion de ce profil doit donc sélectionner le bootstrap de la dimension enregistrée, puis appliquer les deltas publiés jusqu'à la baseline la plus récente.

## Correctif UI 0.5.1

Le premier écran réel de pré-connexion s'affichait correctement mais le flou de menu Minecraft rendait aussi le texte/progress moins lisible sur cette configuration. `ClientSyncScreen` n'utilise plus le blur du menu : il affiche maintenant un voile sombre simple, du texte net et une barre de progression dédiée.

## Optimisation intégrité incrémentale 0.6.2

Le test réel 0.6.1 a mesuré précisément le goulot restant sur une DB Overworld d'environ 9.7 Gio :

- rollback compact : 158624 ms pour seulement 679 lignes à restaurer et 124 clés à supprimer ;
- reverse-delta produit : 14925824 octets ;
- application du delta : 14760 ms ;
- la synchro pré-connexion s'est terminée avec `changed=true` et la connexion Minecraft a repris normalement.

Le scan lent venait du `PRAGMA quick_check` complet de la grosse DB au début du chemin incrémental. Pour un client déjà géré par GabCon, 0.6.2 remplace ces scans complets par des garanties ciblées :

- DB locale obligatoirement inactive avant mutation ;
- delta téléchargé toujours vérifié (taille, SHA-256 et quick_check) ;
- chaîne de baseline validée ;
- reverse-delta compact capturé et quick-checké avant toute mutation ;
- application SQLite atomique en transaction ;
- chaque upsert est relu et comparé exactement au delta avant commit ;
- chaque delete est vérifié absent avant commit ;
- journal de récupération conservé jusqu'au commit d'état.

Les chemins bootstrap/offline de secours gardent les vérifications complètes existantes.

## Optimisation rollback compact 0.6.1

Le premier test réel 0.6.0 a réussi sur la DB client Overworld d'environ 10 Gio, mais `building compact rollback` a encore pris plusieurs minutes pour un delta de 1606 opérations (~40 Mio).

La cause était algorithmique : le reverse-delta 0.6.0 parcourait les grosses tables cibles et cherchait ensuite les clés dans le petit delta. 0.6.1 inverse la requête : il parcourt les seules clés modifiées du delta puis sonde les index de clé primaire des tables DH. Le chemin d'application des suppressions suit la même stratégie. Un `quick_check` complet redondant entre la construction du rollback et l'application directe est également supprimé ; la DB reste vérifiée avant mutation et après la transaction.

## Rollback incrémental compact 0.6.0

Le flux incrémental réel 0.5.x a montré que le téléchargement d'un delta de ~171 MiB était rapide, mais que la copie de préparation de la DB Overworld (~10 Go) prenait encore plusieurs minutes.

0.6.0 introduit un chemin delta-only sans copie complète :

1. vérifier que la DB cible est inactive et saine ;
2. construire un reverse-delta compact à partir des seules clés touchées ;
3. écrire `*.incremental.json` dans le journal de récupération ;
4. appliquer le delta directement dans une transaction SQLite ;
5. exécuter `quick_check` ;
6. mettre à jour la baseline logique dans `client-state.json` ;
7. marquer le journal `STATE_COMMITTED`, puis supprimer le rollback compact.

En cas d'échec ou de crash avant le commit de l'état, les reverse-deltas sont appliqués en ordre inverse. Les tests couvrent aussi le cas ambigu où le forward delta n'a jamais été commit : rejouer le reverse-delta laisse alors l'ancienne DB logiquement inchangée.

La configuration client contient `compactIncrementalApply=true`. La passer à `false` réactive immédiatement le chemin conservateur à copie complète.

## Préparation incrémentale visible 0.5.4

Le premier vrai test incrémental a confirmé que le client télécharge uniquement le nouveau delta publié, sans retélécharger le bootstrap ~10 Go. L'étape suivante `prepare` restait toutefois plusieurs minutes sur `Working...` parce que, par sécurité, GabCon recopie encore la DB Overworld locale complète vers `.gabcon-work` avant d'appliquer le petit delta.

0.5.4 conserve cette stratégie sûre mais affiche désormais la progression réelle de cette copie locale (octets copiés / taille totale et pourcentage) au lieu d'un écran apparemment bloqué. Le fichier original n'est toujours jamais modifié avant le commit final.

## Correctif deadlock téléchargement 0.5.3

Le test réel 0.5.2 a atteint correctement l'étape `download`, mais est resté à 0.0 % sans timeout jusqu'à l'arrêt du jeu. La cause était un partage du même `ExecutorService` entre la tâche de téléchargement GabCon et les tâches internes de `java.net.http.HttpClient`. Avec `maxConcurrentDownloads=1`, l'unique thread pouvait rester bloqué dans `HttpClient.send()` alors que le client HTTP attendait lui-même du travail sur ce pool.

0.5.3 laisse désormais `HttpClient` utiliser son exécuteur interne, tandis que le pool GabCon ne sert qu'à limiter le nombre de téléchargements concurrents. Un test de régression lance volontairement un téléchargement asynchrone avec un pool d'un seul thread et impose une limite de temps.

## Correctif transport GitHub 0.5.2

Un test réel Windows/Java 21 a montré un cas où `manifest.json` était accessible mais les connexions Java vers les gros assets GitHub expiraient avant le premier octet. Avec un seul téléchargement à la fois, l'ancien code essayait quand même les 10 morceaux Overworld + le delta, ce qui pouvait retarder l'erreur d'environ 8 minutes.

0.5.2 durcit ce chemin :

- HTTP/1.1 forcé pour le manifest et les assets GitHub ;
- l'écran passe à `download` **avant** la connexion de l'asset, donc un timeout n'est plus affiché comme un blocage `manifest` ;
- les assets déjà complètement téléchargés sont réutilisés après validation taille + SHA-256 ;
- téléchargement par petits lots limités à `maxConcurrentDownloads` : un échec empêche de lancer les lots suivants ;
- valeur par défaut `maxConcurrentDownloads=1` pour les gros assets Release.

## Validation réelle du bootstrap client 0.5.3

Le bootstrap pré-connexion réel a réussi sur le client Windows/Java 21 avec 0.5.3-mvp :

- écran GabCon pré-connexion affiché ;
- téléchargement du bootstrap terminé ;
- application/commit terminés ;
- connexion Minecraft reprise ensuite ;
- `/gabcondhsyncclient status` affiche désormais `baselines=1` pour `worldId=gabcon-main`.

Cela valide le flux réel `manifest -> bootstrap -> delta chain -> apply -> commit -> connect` sur une DB DH cliente connue.

## Validation réelle finale 0.6.2

La gate de fusion 0.6.2 est validée sur le client réel.

Le delta final publié est :

```text
delta_minecraft_overworld_11ac8ffc9271_063c2940e2ef.sqlite
```

La Release GitHub a montré un téléchargement de ce nouveau delta sans nouveau téléchargement des 10 parties du bootstrap Overworld, ce qui confirme le chemin `delta-only`. Le démarrage client suivant avec `gabcondhsync-0.6.2-mvp.jar` a terminé le pré-connect avec `changed=false`, puis Minecraft a ouvert la connexion au serveur normalement. La commande `/gabcondhsyncclient status` répond ensuite en jeu avec le profil géré, une dimension et une baseline connue.

Le log exact de la première application 0.6.2 n'a pas été conservé, donc les timings du premier passage ne sont pas documentés. La validation fonctionnelle est néanmoins établie par l'état déjà à jour au démarrage suivant, l'absence de rebootstrap et la connexion réussie.
## Build et CI

```powershell
.\gradlew.bat build
```

ou :

```bash
./gradlew build
```

GitHub Actions construit sous Java 21 et exécute les tests SQLite/manifest/downloader/planner. Les données runtime `gabcondhsync/`, DB SQLite, WAL/SHM et `.part` sont ignorés par Git pour éviter une publication accidentelle de données monde.

## Règles de sécurité

- jamais de token dans le client ou le dépôt ;
- jamais de mutation d'une DB DH active ;
- aucune grosse DB dans l'historique Git ;
- bootstrap/deltas uniquement via Release ;
- vérification taille + SHA-256 + `quick_check` ;
- journal crash-safe avant toute mutation incrémentale ;
- reverse-delta compact et rollback multi-dimensions ;
- mode de secours `compactIncrementalApply=false` ;
- fusion autorisée après validation réelle 0.6.2 et CI verte.
