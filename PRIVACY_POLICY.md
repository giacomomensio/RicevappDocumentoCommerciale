# Informativa sulla Privacy - Ricevapp Documento Commerciale

**Ultimo aggiornamento:** Dicembre 2025

Questa informativa descrive le modalità di gestione dei dati personali dell'utente da parte dell'applicazione **Ricevapp Documento Commerciale**.

### 1. Natura dell'Applicazione
Ricevapp Documento Commerciale è un'applicazione "client" non ufficiale che permette di accedere in modo semplificato al portale web dell'Agenzia delle Entrate per l'emissione di documenti commerciali. L'applicazione funge da browser (WebView) ottimizzato per tale servizio.

### 2. Raccolta e Utilizzo dei Dati

#### A. Credenziali di Accesso (Opzionale)
L'applicazione offre all'utente la possibilità di salvare le proprie credenziali di accesso (Nome utente, Password e PIN) per facilitare i login futuri.
*   **Come vengono salvati:** Se l'utente sceglie di utilizzare questa funzione, le credenziali vengono salvate **esclusivamente nella memoria locale del dispositivo**.
*   **Sicurezza:** I dati vengono crittografati utilizzando gli standard di sicurezza Android (`EncryptedSharedPreferences` e Android Keystore System), rendendoli inaccessibili ad altre applicazioni o ad attacchi esterni.
*   **Nessuna trasmissione:** Le credenziali **NON** vengono mai inviate allo sviluppatore, né a server di terze parti, né salvate su cloud. Esse vengono utilizzate dall'app esclusivamente per compilare automaticamente i campi di login sulla pagina ufficiale dell'Agenzia delle Entrate visualizzata all'interno dell'app.

#### B. Dati Biometrici
L'applicazione utilizza i sensori biometrici del dispositivo (impronta digitale o riconoscimento facciale) per proteggere l'accesso all'applicazione stessa.
*   L'applicazione **non raccoglie, non memorizza e non ha accesso** ai dati biometrici dell'utente (l'immagine dell'impronta o del volto).
*   L'autenticazione viene gestita interamente dal sistema operativo Android, che comunica all'applicazione solo l'esito (Successo/Fallimento).

### 3. Navigazione e Dati di Rete
L'applicazione visualizza direttamente il sito web dell'Agenzia delle Entrate. Qualsiasi dato inserito durante la navigazione (es. dati delle fatture, importi, codici fiscali) viene trasmesso direttamente dal dispositivo dell'utente ai server dell'Agenzia delle Entrate, esattamente come avverrebbe utilizzando un normale browser (Chrome, Firefox). Lo sviluppatore dell'app non ha accesso a questi dati.

### 4. Servizi di Terze Parti
Questa applicazione:
*   **Non** contiene pubblicità.
*   **Non** utilizza strumenti di analisi o tracciamento (come Google Analytics o Firebase) che raccolgono dati sull'utilizzo.

### 5. Gestione dei Dati
L'utente ha il pieno controllo dei propri dati:
*   È possibile rimuovere le credenziali salvate in qualsiasi momento deselezionando la casella "Salva credenziali" nella schermata di login o cancellando i dati dell'app dalle impostazioni di Android.
*   Disinstallando l'applicazione, tutti i dati salvati localmente verranno eliminati definitivamente dal dispositivo.

### 6. Contatti
Per domande o chiarimenti riguardanti questa informativa sulla privacy, è possibile contattare lo sviluppatore all'indirizzo:
giacomomensio.playstore@outlook.it