<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHInteractReset

*Réinitialise l'état des blocs interactifs (portes, trappes) après un délai dans des mondes spécifiques.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Réinitialise l'état des blocs interactifs (portes, trappes) après un délai dans des mondes spécifiques.

Utilisé sur : **Opale**.

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/shinteractresetreload` | — | Recharge la configuration du plugin SHInteractReset. | `shinteractreset.reload` |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shinteractreset.reload` | Permet de recharger la configuration du plugin. | `op` |
| `shinteractreset.bypass` | Permet au joueur d'ignorer la réinitialisation automatique des blocs. | `op` |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`) :

- `config.yml`

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
