# GabCon DH Sync

Mod **NeoForge 1.21.1 / Java 21** destiné à réduire le trafic Distant Horizons servi directement par un serveur Minecraft domestique en préparant une distribution externe par **GitHub Releases**.

> **État : MVP snapshot + delta.** Le serveur sait créer des snapshots SQLite cohérents puis comparer les deux derniers snapshots pour produire un delta logique exact (upserts + suppressions) sans décoder les BLOB Distant Horizons. La publication GitHub et l'import transactionnel côté client restent désactivés tant que le round-trip n'a pas été validé.

## Cible testée

- Minecraft Java Edition `1.21.1`
- NeoForge `21.1.251`
- Java `21`
- Distant Horizons `3.3.2` (`DistantHorizons-3.3.2-1.21.1-fabric-neoforge.jar`)
- API DH embarquée dans ce JAR : `7.2.0`
- Paire legacy encore acceptée par le détecteur : DH `3.3.1` / API `7.1.0`

Le même JAR GabCon DH Sync est prévu pour le client et le serveur. Distant Horizons n'est **pas** embarqué dans le JAR GabCon.

## Ce qui a été vérifié dans DH 3.3.2

L'analyse du JAR exact 3.3.2 et la comparaison binaire avec le JAR 3.3.1 ont confirmé les points suivants :

1. `DhApi.getApiMajorVersion()/Minor/Patch()` retourne désormais `7.2.0` (contre `7.1.0` en DH 3.3.1).
2. Le protocole réseau DH reste à `16` entre 3.3.1 et 3.3.2.
3. `IDhApiTerrainDataRepo` est inchangé. `overwriteChunkDataAsync(...)` existe toujours, mais reste une API alimentée par des objets chunks Minecraft ; ce n'est toujours pas une API générique d'import de LOD sérialisées.
4. L'API 7.2.0 ajoute notamment `IDhApiConfigValue.setValue(value, modName)` ainsi que des informations de profondeur de rendu (`getDepthRange()`, `getDepthDirection()`). Ces ajouts ne fournissent pas de mécanisme de snapshot/import pour notre cas.
5. Les scripts SQLite embarqués `0010` à `0110` sont identiques entre 3.3.1 et 3.3.2, y compris `journal_mode = WAL` et `synchronous = NORMAL`.
6. DH possède toujours en interne un chemin réseau basé notamment sur `FullDataSourceResponseMessage` / `FullDataSourceV2DTO`, mais ces classes ne font pas partie de l'API publique et le MVP ne les utilise pas.
7. Aucune nouvelle API publique vérifiée n'a été trouvée pour fermer/checkpointer/exporter puis réimporter un snapshot LOD sérialisé en sécurité.

Deux API publiques DH 3.3.2 sont en revanche suffisantes pour sécuriser le snapshot serveur : `IDhApiWorldProxy.setReadOnly(...)` permet de geler temporairement les mises à jour LOD et `IDhApiLevelWrapper.getDhSaveFolder()` donne le dossier exact de chaque DB chargée. GabCon utilise ensuite le mécanisme SQLite `backup` du pilote `dh_sqlite` embarqué par DH, vérifie la copie avec `PRAGMA quick_check`, calcule son SHA-256 puis restaure le mode lecture/écriture de DH.

### Base de test GabCon analysée

Le fichier `test-data/dh/DistantHorizons-GabCon-test.zip` a été inspecté automatiquement en CI, en lecture seule :

- archive : 15 326 512 octets, SHA-256 `5016c32cfdb9f0b3c1528edc1ba8e47ebab33fbe97f3314eb5a3c0f972b075c4` ;
- DB extraite : 15 699 968 octets, SHA-256 `37d697e3df940dc38aecba82e44f1416901563bb8ba545a9a25748d3cc59a53c` ;
- `PRAGMA quick_check = ok` ;
- tables actives : `FullData`, `ChunkHash`, `BeaconBeam`, `Schema` ;
- `FullData` : 360 lignes, detail levels 0 à 8, format de données 2, compression 4 ;
- `ChunkHash` : 1 959 lignes ;
- `Legacy_FullData_V1` : 0 ligne.

Cette DB confirme le schéma V2 que le futur générateur de deltas devra comparer par clés primaires et checksums, sans interpréter ni réencoder les BLOB DH.

## Architecture du MVP

### Serveur — source de vérité

GabCon écoute `ChunkDataEvent.Save` de NeoForge. Chaque sauvegarde de chunk est regroupée dans un **bucket de publication 32×32 chunks** : ce regroupement sert uniquement à la file de publication et ne suppose rien sur la géométrie de stockage interne de DH.

Les dimensions sont identifiées par leur ResourceLocation (`minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end`, dimensions moddées).

### Manifest

Schéma `1` :

```json
{
  "schemaVersion": 1,
  "worldId": "gabcon-world-01",
  "minecraftVersion": "1.21.1",
  "neoforgeVersion": "21.1.251",
  "distantHorizonsVersion": "3.3.2",
  "baseVersion": 1,
  "latestDelta": 128,
  "dimensions": {
    "minecraft:overworld": {
      "bootstrap": {
        "version": 1,
        "fileName": "overworld-base-v1.gcdh",
        "size": 123456789,
        "sha256": "<64 hex>",
        "url": "https://github.com/.../overworld-base-v1.gcdh",
        "requiresBaseVersion": 1
      },
      "deltas": []
    }
  }
}
```

Validation actuelle : version de schéma, `worldId`, HTTPS uniquement, taille, SHA‑256, nom de fichier sûr, ordre/duplication des deltas et dépendance à la base.

### Sélection différentielle

Si le client possède `baseVersion=1` et `lastDelta=124`, et le manifest va jusqu'à `128`, la sélection retient uniquement `125..128`. Si la version de base diffère, le bootstrap est repris avant les deltas.

### Download manager

Déjà implémenté :

- asynchrone hors thread graphique ;
- HTTPS uniquement en production ;
- `.part` ;
- reprise HTTP `Range` ;
- fallback sûr si le serveur ignore `Range` ;
- timeout et retries bornés ;
- limite de taille ;
- taille finale attendue ;
- SHA‑256 ;
- remplacement atomique lorsque le système de fichiers le permet ;
- nettoyage d'un `.part` dont le hash est faux ;
- protection contre path traversal dans les noms d'assets ;
- progression, vitesse et ETA exposées à la future GUI.

## Commandes

```text
/gabcondhsync status
/gabcondhsync snapshot
/gabcondhsync delta
/gabcondhsync publish
/gabcondhsync reload
```

`status` affiche notamment le nombre de buckets en attente, `snapshotRunning`, `deltaRunning` et la paire DH/API détectée.

`/gabcondhsync snapshot` est **actif**. Il met temporairement DH en lecture seule via l'API publique, crée une copie cohérente avec le backup SQLite en ligne, vérifie chaque DB et écrit un `snapshot.json`. Les fichiers sont placés sous `gabcondhsync/snapshots/<worldId>/<timestamp UTC>/`.

`/gabcondhsync delta` est **actif**. Il choisit les deux snapshots valides les plus récents de `gabcondhsync/snapshots/<worldId>/`, exige le même `worldId`, la même version DH/API et le même schéma SQLite, puis compare les tables `FullData`, `ChunkHash` et `BeaconBeam`. Pour chaque dimension réellement modifiée il crée un SQLite de delta sous `gabcondhsync/deltas/<worldId>/<from>--<to>/`.

Chaque delta contient des tables `<Table>Upsert` avec les lignes ajoutées/modifiées et `<Table>Delete` avec uniquement les clés primaires à supprimer. Les BLOB DH sont copiés octet pour octet : GabCon ne les décode ni ne les réencode. `Legacy_FullData_V1` doit être vide et la table `Schema` doit être identique entre les deux snapshots, sinon le delta est refusé.

`/gabcondhsync publish` reste volontairement désactivé tant que l'import hors ligne transactionnel côté client n'est pas validé.

## Configuration

### Serveur

Fichier NeoForge serveur généré pour le mod :

- `enabled`
- `repository`
- `worldId` — `gabcon-main` (l'ancienne valeur `CHANGE_ME` est migrée automatiquement au démarrage)
- `publishIntervalMinutes`
- `changedRegionThreshold`
- `nativeDhFallbackEnabled`
- `autoPublish`
- `maxDownloadBytes`

`autoPublish` reste ignoré par sécurité tant que GitHub Releases + deltas + import client ne sont pas validés.

### Client

- `enabled`
- `autoDownload`
- `connectOnComplete`
- `allowFallback`
- `maxConcurrentDownloads`
- `optionalDownloadSpeedLimit`

La GUI et l'interception de connexion seront branchées lorsque le format/import LOD aura été validé.

## Sécurité GitHub

- aucun PAT/token dans le client ;
- aucun token dans le dépôt ;
- le futur publisher serveur lira `GABCON_DH_GITHUB_TOKEN` depuis l'environnement ;
- ce token devra être limité à ce dépôt ;
- les clients téléchargeront des assets publics GitHub Releases sans token.

Le code Git contient le code, la CI, les schémas/manifests et la documentation. Les grosses bases/archives DH doivent aller dans **GitHub Releases**, jamais dans l'historique Git.

## Build

Windows :

```powershell
.\gradlew.bat build
```

Linux/macOS :

```bash
./gradlew build
```

Le JAR est généré dans `build/libs/`.

## Tests présents

- parsing/validation manifest ;
- mauvaise `worldId` ;
- version de schéma/migration non prise en charge ;
- sélection de deltas ;
- client déjà à jour ;
- SHA‑256 ;
- path traversal ;
- reprise d'un `.part` par HTTP Range ;
- hash incorrect ;
- téléchargement incomplet conservé pour reprise ;
- regroupement de chunks avec coordonnées négatives ;
- backup SQLite en ligne via un shim `dh_sqlite` de test ;
- `PRAGMA quick_check` sur le snapshot ;
- normalisation sûre des noms de dimensions ;
- analyse CI en lecture seule de la DB DH de test réelle ;
- génération de deltas SQLite avec upserts/suppressions ;
- comparaison null-safe des colonnes, BLOB compris ;
- refus si le schéma DH diffère ou si des données legacy subsistent ;
- cas où deux snapshots ont des hashes physiques différents mais zéro différence logique.

## CI / Releases

`.github/workflows/build.yml` lance Java 21 + `./gradlew build` sur push/PR et publie le JAR comme artifact GitHub Actions.

`.github/workflows/release.yml` construit et crée une GitHub Release lors d'un tag `v*` avec le `GITHUB_TOKEN` éphémère de GitHub Actions. Aucun secret client n'est requis.

## Étape DH suivante (expérimentale)

Le prochain jalon est le test réel de `/gabcondhsync delta` sur les deux snapshots GabCon déjà produits. Le résultat permettra de mesurer combien de **lignes logiques** ont réellement changé, indépendamment des différences physiques de pages SQLite.

Après validation du `delta.json` :

1. implémenter l'importeur client hors ligne dans une copie temporaire de la DB ;
2. appliquer les suppressions puis les upserts dans une transaction ;
3. exécuter `PRAGMA quick_check` et vérifier SHA-256 / `worldId=gabcon-main` / compatibilité DH ;
4. conserver un rollback avant remplacement atomique de la DB client ;
5. ensuite seulement activer `publish`, GitHub Releases, bootstrap segmenté et interception de connexion client.

## Validation serveur réelle du snapshot

Le premier snapshot réel du serveur GabCon avec DH 3.3.2 a produit :

- `minecraft:overworld` : `10,406,621,184` octets ;
- `minecraft:the_nether` : `61,440` octets ;
- `minecraft:the_end` : `61,440` octets ;
- Nether et End ont le même SHA-256 dans ce snapshot, ce qui indique des bases identiques à ce stade.

Le manifest a été produit après le backup SQLite et les `quick_check`, donc les trois snapshots ont franchi la validation locale de GabCon.

Un second snapshot réel a ensuite été créé à `2026-09-22T14:15:55Z`. Sa taille Overworld reste exactement `10,406,621,184` octets, mais son SHA-256 passe de `be76254f…d1104c` à `5e38fd6f…0d3bd`. Nether et End sont inchangés. Cela confirme qu'un hash de fichier détecte une évolution physique, mais **ne dit pas combien de lignes DH ont changé** ; le générateur de delta logique sert précisément à répondre à cette question.

### Conséquence pour GitHub Releases

GitHub impose que chaque asset de release fasse moins de 2 GiB. L'Overworld de plus de 10 Go ne peut donc jamais être envoyé comme un unique fichier.

Le futur bootstrap sera **segmenté** en morceaux nettement inférieurs à 2 GiB, chacun avec taille + SHA-256 dans le manifest. Le client reconstruira le fichier dans un emplacement temporaire, validera le SHA-256 du fichier complet, puis seulement l'utilisera. Les deltas resteront des assets séparés et beaucoup plus petits.

Les grosses DB/snapshots ne doivent jamais être ajoutés à l'historique Git.

## Récupération / rollback

Le MVP ne modifie pas les DB DH, donc sa désinstallation consiste simplement à retirer son JAR. Pour les futures versions qui importeront des données, la règle de conception est : fichier temporaire, vérification complète, sauvegarde/rollback documenté et aucune tentative de "forcer" un manifest ou un `worldId` incompatible.
