# AutoTasker

Application Android d'automatisation locale, conçue pour consommer le strict minimum
en arrière-plan. Deux besoins précis à l'origine :

1. **Routine voiture** — quand le téléphone est *en charge sans fil* **et** *connecté au
   Bluetooth de la voiture*, lancer automatiquement une suite d'applications.
2. **Réveil d'applications** — forcer l'exécution des tâches de fond d'applis comme
   *Home Assistant* ou *DS cam*, qui ne se synchronisent réellement que quand on les
   ouvre (le mode « maison » de DS cam qui ne se coupe pas quand on part, typiquement).

---

## Le principe : presque zéro tâche de fond

| Événement | Mécanisme | Coût |
|---|---|---|
| Bluetooth connecté / déconnecté | `ACL_CONNECTED` / `ACL_DISCONNECTED` déclarés au manifeste | réveil de quelques ms |
| Redémarrage du téléphone | `BOOT_COMPLETED` | une fois |
| Réveil périodique *(optionnel)* | WorkManager, ≥ 15 min | groupé avec les autres applis, respecte le Doze |
| Début / fin de charge | **service d'écoute résident** — voir ci-dessous | notification permanente |

Les diffusions Bluetooth et de démarrage font partie des rares diffusions implicites
encore autorisées dans le manifeste depuis Android 8 : le processus est réveillé au
moment exact de l'événement, lit l'état de l'appareil, compare avec l'état précédent,
décide, puis s'éteint. Entre deux événements, l'appli n'existe pas.

### L'exception : la charge

`ACTION_POWER_CONNECTED` et `ACTION_POWER_DISCONNECTED` **ne figurent pas** sur la
[liste des exceptions](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions).
Un receiver déclaré au manifeste pour ces actions n'est **jamais** appelé sur Android 8+.
La seule façon de les recevoir est de les enregistrer à l'exécution, ce qui suppose un
processus vivant — d'où `PowerWatchService` et sa notification permanente.

Ce service ne démarre que si au moins une règle activée utilise un déclencheur lié à la
charge. Une configuration purement Bluetooth ne fait tourner aucun service. Le coût reste
modéré : le service ne tient aucun wakelock et ne réveille jamais le processeur, il
maintient seulement deux filtres d'intention — contrairement à une écoute de
`ACTION_BATTERY_CHANGED`, qui serait notifiée à chaque pourcent.

La notification peut être masquée depuis les réglages Android (appui long → catégorie
« Surveillance du chargeur ») sans désactiver la surveillance.

Enfin, un service de premier plan éphémère existe **pendant** l'exécution d'une séquence,
soit quelques secondes.

### Le moteur

À chaque événement, une « photo » de l'appareil est prise
(`charge sans fil`, `en charge`, `Bluetooth voiture connecté`, `Wi-Fi`, `SSID`).
Les déclencheurs sont déduits de la **différence** avec la photo précédente — donc des
fronts montants/descendants, jamais d'un état permanent. Le mode voiture, par exemple,
est simplement `charge sans fil ET Bluetooth voiture`, et `CAR_MODE_START` se déclenche
au moment où cette combinaison devient vraie, quel que soit l'ordre des deux événements.

---

## Installation

### Lien direct, toujours le même

```
https://github.com/Oobilsteinoo/vscode-fanuc-karel/releases/download/autotasker-latest/autotasker.apk
```

À ouvrir depuis le navigateur du téléphone : pas de compte GitHub, pas de zip. Cette URL
pointe toujours vers le dernier build réussi — mettez-la en favori.

### Mise à jour sans désinstaller

L'APK est signé avec la clé versionnée dans `keystore/`, et la CI incrémente le `versionCode`
à chaque build (numéro de run). Une nouvelle version s'installe donc **par-dessus**
l'ancienne, en conservant vos règles et vos appareils Bluetooth.

> **Signature.** Cette clé est publique et ce n'est pas un oubli. Sans clé stable, chaque
> build de la CI serait signé par une clé de debug régénérée à neuf, et Android refuserait
> toute installation par-dessus la précédente — il faudrait désinstaller à chaque fois.
> Ce n'est pas un identifiant : elle ne donne accès à aucun compte ni service. Son seul
> pouvoir est de signer un APK qu'Android accepterait comme mise à jour de `com.autotasker`,
> ce qui suppose déjà de vous faire installer un fichier à la main. Pour une appli
> personnelle distribuée hors Play Store, le compromis est délibéré. Pour la rendre privée :
> déplacer le `.jks` en secret GitHub (`base64 -w0`) et le décoder dans le workflow.

### Construire soi-même

```bash
cd android
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

Nécessite un JDK 17 et le SDK Android (API 35). Android Studio fait tout ça tout seul :
ouvrir le dossier `android/`. Attention, un build local produit `versionCode = 1` : pour
réinstaller par-dessus une version issue de la CI, passez `AUTOTASKER_VERSION_CODE` à une
valeur supérieure.

## Configuration

### Autorisations — à faire en premier

L'écran **Réglages & autorisations** liste tout, mais deux d'entre elles ne sont pas
négociables :

- **Affichage par-dessus les autres applis.** Depuis Android 10, une appli en
  arrière-plan n'a plus le droit d'ouvrir une autre application. Cette autorisation est
  la seule exemption officielle (c'est aussi ce qu'utilisent Tasker et MacroDroid).
  Sans elle les routines se déclencheront — le journal le montrera — mais **rien ne
  s'ouvrira**.
- **Ignorer l'optimisation de la batterie.** Empêche Android de retarder ou d'ignorer
  les réveils. Comme l'appli ne fait rien tant qu'aucun événement ne survient, cette
  exemption ne coûte pas de batterie par elle-même.

Sur Xiaomi / Huawei / Oppo / OnePlus / Samsung, il faut **en plus** autoriser le
démarrage automatique dans le gestionnaire maison du constructeur, sinon les
diffusions Bluetooth et charge ne seront jamais reçues.

### Tâche 1 — routine voiture

1. Écran d'accueil → **Appareils Bluetooth « voiture »** → cocher l'autoradio.
2. Ouvrir la règle **Routine voiture** (déclencheur *Charge sans fil + Bluetooth voiture*).
3. Ajouter les actions dans l'ordre voulu, par exemple :
   - `Lancer` Android Auto — `Attendre` 5 s — `Lancer` Waze — `Attendre` 3 s —
     `Lancer` Spotify — `Lecture média`.
4. Le bouton **Lancer** teste la séquence immédiatement, sans attendre le déclencheur.

Le *délai anti-répétition* (2 min par défaut) évite qu'un Bluetooth capricieux relance
la séquence à chaque micro-coupure.

### Sortie de voiture

La règle **Sortie de voiture** est pré-créée, déclenchée par *Bluetooth déconnecté* sur les
appareils de la liste voiture. Ce déclencheur est délibérément basé sur le Bluetooth seul et
non sur la fin du « mode voiture » : il fonctionne même si le téléphone n'était pas posé sur
le chargeur pendant le trajet.

Exemple de séquence : `Pause média` — `Fermer` Waze — `Fermer` Android Auto — `Retour à l'accueil`.

Pour une règle qui ne doit s'exécuter qu'une fois vraiment sorti du véhicule, ajoutez les
conditions **Bluetooth voiture déconnecté** et **Pas en charge** : elles filtrent les
déconnexions parasites survenant moteur tournant.

Le déclencheur *Fin charge sans fil / Bluetooth voiture* (`CAR_MODE_END`) reste disponible si
vous préférez exiger que les deux signaux retombent.

### Tâche 2 — réveiller les applis fantômes

« Réveiller » = ouvrir l'application, la laisser au premier plan quelques secondes
(le temps que son `onResume` déclenche sa synchronisation), puis revenir à l'accueil.
C'est exactement ce que vous faites à la main aujourd'hui, en automatique.

Deux règles sont pré-créées :

- **Réveil des applis (départ en voiture)** — déclencheur *Bluetooth connecté*.
  Prendre la voiture = quitter la maison : c'est le signal le plus fiable et le moins
  cher en batterie pour que DS cam sorte du mode « maison ».
  Ajouter une action `Réveiller une application` par appli (DS cam, Home Assistant…),
  4 s chacune.
- **Réveil périodique des applis** *(désactivée par défaut)* — filet de sécurité toutes
  les 30 min, avec la condition **écran éteint** : l'ouverture est alors totalement
  invisible. Activez-la si le déclencheur Bluetooth ne suffit pas.

Autres déclencheurs disponibles pour ce cas : *Wi-Fi perdu* (sortie de la maison) et
*Wi-Fi rejoint* (retour). Voir les limites plus bas.

> Pensez aussi à retirer DS cam et Home Assistant de l'optimisation de batterie : le
> réveil les relance, mais Android peut toujours tuer leur propre travail de fond ensuite.

---

## Types d'actions

| Action | Effet |
|---|---|
| `Lancer une application` | Ouvre l'appli et la laisse au premier plan |
| `Réveiller une application` | Ouvre l'appli, attend N secondes, revient à l'accueil |
| `Fermer une application` | Demande la fin de ses processus d'arrière-plan — **voir la limite ci-dessous** |
| `Attendre` | Pause entre deux actions |
| `Retour à l'accueil` | Envoie l'intent HOME : met l'appli au premier plan en arrière-plan |
| `Ouvrir un lien` | URL ou lien d'appli (`https://`, `dscam://`…) |
| `Lecture média` | Touche « Play » (reprend Spotify, etc.) |
| `Pause média` | Touche « Pause » (coupe la musique en sortant de voiture) |

> **Fermer une application, honnêtement.** Android n'expose aucune API publique permettant de
> terminer une application tierce. `Fermer une application` appelle `killBackgroundProcesses`,
> qui fonctionne jusqu'à Android 13 mais que **Android 14 a restreint à l'appli appelante** :
> sur les versions récentes l'appel est silencieusement ignoré (le journal le signale).
> Pour obtenir l'effet recherché en sortant de voiture, utilisez plutôt `Pause média` — qui
> coupe réellement la lecture — et `Retour à l'accueil`, qui bascule l'appli au premier plan
> en arrière-plan où Android la recyclera de lui-même.

## Déclencheurs et conditions

Déclencheurs : mode voiture (début/fin), Bluetooth connecté/déconnecté (appareil
précis ou n'importe lequel de la liste voiture), début/fin de charge sans fil, début/fin
de charge, Wi-Fi perdu/rejoint, périodique, démarrage du téléphone, manuel.

Conditions (filtres cumulatifs vérifiés au moment du déclenchement) : charge sans fil,
en charge, **pas en charge**, Bluetooth voiture connecté, **Bluetooth voiture déconnecté**,
écran éteint, écran allumé, Wi-Fi, hors Wi-Fi, **plage horaire** (passage de minuit géré).

Les conditions contradictoires se désactivent mutuellement dans l'éditeur : cocher « pas en
charge » décoche « en charge », etc.

Le **journal** (icône ⓘ) trace chaque déclenchement, chaque exécution, et surtout
chaque règle *écartée* avec la raison (conditions non remplies, délai anti-répétition,
aucune action) — c'est le premier endroit à regarder quand « ça n'a pas marché ».

---

## Limites connues

- **Pas de géorepérage.** « Je sors de chez moi » est approximé par la connexion au
  Bluetooth de la voiture, la perte du Wi-Fi, ou le réveil périodique. Un vrai
  géorepérage demanderait les services Google Play et l'autorisation de localisation en
  arrière-plan ; le moteur est structuré pour l'accueillir plus tard
  (`TriggerType` + `Engine.firedTriggers`).
- **SSID Wi-Fi rarement lisible.** Depuis Android 8.1, lire le nom du réseau exige
  l'autorisation de localisation, que l'appli ne demande pas. Les règles Wi-Fi se
  rabattent donc sur « connecté / pas connecté », ce qui suffit si le Wi-Fi de la maison
  est le seul auquel le téléphone se connecte habituellement.
- **Perte de Wi-Fi détectée avec retard.** Android ne diffuse plus le changement d'état
  réseau aux receivers déclarés dans le manifeste. La transition est donc constatée au
  prochain événement (charge, Bluetooth) ou au prochain réveil périodique.
- **Fermer une application est impossible sur Android 14+.** Détaillé plus haut : aucune API
  publique ne le permet sans service d'accessibilité, lequel serait résident en mémoire et
  irait à l'encontre de l'objectif de consommation minimale.
- **La charge impose un service résident.** Détaillé plus haut. Si vous n'utilisez que des
  déclencheurs Bluetooth, aucun service ne tourne et l'appli est réellement invisible.
- **Lancement en arrière-plan.** Si l'autorisation d'affichage par-dessus les autres
  applis manque, ou si le constructeur bloque le démarrage automatique, les séquences ne
  s'exécuteront pas. Un repli via WorkManager existe si Android refuse le service de
  premier plan, mais il ne contourne pas l'absence d'autorisation.

## Structure du code

```
app/src/main/java/com/autotasker/
├── App.kt                     Application : init du stockage, canal de notif, planification
├── MainActivity.kt            Hôte Compose et navigation (4 écrans, sans dépendance nav)
├── data/                      Modèle (règles, déclencheurs, actions), stockage JSON, journal
├── core/                      Lectures d'état : batterie, écran, Wi-Fi, Bluetooth, applis
├── engine/                    Moteur de règles (photo → différences → règles) et exécuteur
├── receiver/                  L'unique BroadcastReceiver du manifeste
├── service/                   Exécution d'une séquence + écoute résidente du chargeur
├── work/                      Réveil périodique + repli d'exécution (WorkManager)
└── ui/                        Écrans Compose (accueil, éditeur, journal, réglages)
```

Le **journal détaillé** (activé par défaut, désactivable dans les réglages) consigne aussi
les réveils sans effet : c'est ce qui permet de distinguer « l'appli n'a jamais été
réveillée » de « elle a été réveillée mais aucune règle ne correspondait ».

Aucune dépendance réseau, aucune analytique, aucune donnée ne quitte le téléphone :
la configuration tient dans un unique blob JSON en `SharedPreferences`.
