# Create a game

Install a DOS game from its own installer disks onto a new, empty C: drive. The installer's floppy images, CD image or folder can stay wherever they are (for example in Download): the Disks page mounts them from anywhere on the device. Nothing is written to your games folder until the new drive holds a file; then it is saved there as `<name>.zip`, a game like any other.

## 1. Start a new game on an empty C: drive

Open the library menu › **Manage library**. Under **New game**, choose **Empty C: drive**.

![New game in Manage library](screenshots/create-a-game/01-new-game.png)

Name the game and press **Create**.

![Naming the new game](screenshots/create-a-game/02-name.png)

The game starts at the DOS prompt on an empty C:.

![The empty C: drive](screenshots/create-a-game/03-empty-c.png)

## 2. Mount the installer

Open the menu › **Disks**. Drive A and Drive B take floppy images (.img, .ima), the CD-ROM takes a CD image (.iso); each also takes a folder or a zip.

![Disks](screenshots/create-a-game/04-disks.png)

Choose the drive, then **Choose file…** for an image or **Choose folder…** for a folder.

![Drive A](screenshots/create-a-game/05-drive-a.png)

![Choosing the installer's folder](screenshots/create-a-game/06-choose-folder.png)

![Drive A holds the installer](screenshots/create-a-game/07-drive-a-holds.png)

`dir a:` lists the installer's files.

![The installer on A:](screenshots/create-a-game/08-dir-a.png)

## 3. Install

Run the game's installer and install to C:, or copy the files yourself. Here: `md persia`, `xcopy a:\*.* c:\persia /s`.

![Installing to C:](screenshots/create-a-game/09-install.png)

Start the game from C: to try it. Restart game keeps the Disks drives as they are.

![The game runs from C:](screenshots/create-a-game/10-runs-from-c.png)

## 4. Save it to your library

Leave the game (Return to library or Exit). KairoDos saves the new drive to your games folder as `<name>.zip`; it appears in the library and plays like any other game. If the catalog knows the installed game, it gets the game's title, artwork and setup.

![The new game in the library](screenshots/create-a-game/11-listed.png)

![It plays from the library](screenshots/create-a-game/12-plays-from-library.png)

A new game left empty leaves nothing behind. If the app is stopped before the session ends, the new game is saved the next time KairoDos opens. From then on, what the game writes is kept in its saves, as for every KairoDos game.
