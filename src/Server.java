import java.io.*;
import java.net.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.Random;

class Server {
	private ServerSocket socket;
	private int playerCount; //limiting to 2
	private ArrayList<Pokemon> pokemonList;

	private ServerSideConnection playerOne;
	private ServerSideConnection playerTwo;
	private int roundCount = 0;
	private int readyCount = 0;
	private boolean calculateBool = false;
	private Connection dbconn;

	private enum SERVER_STATE {WAITING_ON_PLAYERS, START, PLAYER_1_TURN, PLAYER_2_TURN, CALCULATING, END}
	private SERVER_STATE myState;

	//default constructor
	public Server() {
		System.out.println("----------------------\n\tServer\n----------------------");
		connectToDatabase();
		playerCount = 0;
		this.myState = SERVER_STATE.WAITING_ON_PLAYERS;
		try {
			socket = new ServerSocket(25565);
		} catch (IOException ex) { System.out.println("IO Exception from default constructor."); }
	}

	private void connectToDatabase(){
		System.out.println("Initializing Database...");
		try {
			dbconn = DriverManager.getConnection("jdbc:sqlite:pokemon.db");
			ResultSet pokemon = dbconn.prepareStatement("SELECT * FROM pokemon").executeQuery();
			this.pokemonList = new ArrayList<Pokemon>();
			while (pokemon.next()){
				//Now we need to construct the Pokemon objects.
				//We'll grab all of their moves
				int id = pokemon.getInt(1);
				//Got the ID of this pokemon, now we need to iterate and get all of their moves.
				PreparedStatement movesPS = dbconn.prepareStatement("SELECT * FROM pokemon_moves WHERE id = ? ORDER BY sequence");
				movesPS.setInt(1, id);
				ResultSet movesRS = movesPS.executeQuery();
				ArrayList<PokemonMove> tempMoves = new ArrayList<PokemonMove>();
				while (movesRS.next()){
					//Now we need to create the moves for this Pokemon
//					System.out.println("Got move: " + movesRS.getString("name"));
					tempMoves.add(new PokemonMove(movesRS.getString("name"), movesRS.getString("type"), movesRS.getInt("minDamage"), movesRS.getInt("maxDamage"), movesRS.getDouble("critChance"), movesRS.getInt("sequence")));
				}
				Pokemon temp = new Pokemon(pokemon.getInt("id"), pokemon.getString("name"), pokemon.getString("type"), pokemon.getInt("hp"), tempMoves);
				this.pokemonList.add(temp);
			}
		} catch (SQLException e){
			System.out.println("Caught SQLException!");
			e.printStackTrace();
			System.exit(1);
		}
	}


	private void broadcastMessageToAllPlayers(String msg){
		try {
			this.playerTwo.sendMessageToPlayer(msg);
			this.playerOne.sendMessageToPlayer(msg);
		} catch (Exception e){
			e.printStackTrace();
		}
	}

	private void stateHandler(String message, String option, ServerSideConnection scc){
		//here we'll handle the state transitions based on our current state
		if (this.myState == SERVER_STATE.WAITING_ON_PLAYERS){
			//if we're waiting on players...
			if (message.equals("ALL_READY")){
				//Waiting on players and we're all ready...
				myState = SERVER_STATE.START;
				broadcastMessageToAllPlayers("ALL_READY -1");
				randomizePlayerTurn();
				return;
			} else if (message.equals("POKEMON_READY")){
				scc.setPokemon(this.pokemonList.get(Integer.parseInt(option)));
				readyCount++;
				if (readyCount == 2){
					stateHandler("ALL_READY", "0", null);
				}
			}
			return;
		}
		if(this.myState == SERVER_STATE.START){
			if (message.equals("PLAYER_1_TURN") && this.playerOne == scc){
				this.myState = SERVER_STATE.PLAYER_1_TURN;
				return;
			} else {
				this.myState = SERVER_STATE.PLAYER_2_TURN;
				return;
			}
		}
		if (this.myState == SERVER_STATE.PLAYER_1_TURN){
			if (message.equals("SEND_TURN") && this.playerOne == scc){
				this.myState = SERVER_STATE.PLAYER_2_TURN;
				//Handle their turn:
				handleDamage(scc, playerTwo, Integer.parseInt(option.split("_")[1]));
				try{
					Thread.sleep(100);
				} catch (InterruptedException e){
					e.printStackTrace();
					System.exit(1);
				}
				playerTwoTurn();
				return;
			}
		}
		if (this.myState == SERVER_STATE.PLAYER_2_TURN){
			if (message.equals("SEND_TURN") && this.playerTwo == scc){
				this.myState = SERVER_STATE.PLAYER_1_TURN;
				handleDamage(scc, playerOne, Integer.parseInt(option.split("_")[1]));
				try{
					Thread.sleep(100);
				} catch (InterruptedException e){
					e.printStackTrace();
					System.exit(1);
				}
				playerOneTurn();
				return;
			}//add if statement for if the message is other players turn
		}
		if (this.myState == SERVER_STATE.CALCULATING){

		}
		if (this.myState == SERVER_STATE.END){

		}
		System.out.println("|||StateHandler|||");
		System.out.println("curState: " + this.myState + " | Input: " + message + " " + option);
	}

	private void randomizePlayerTurn(){
		//Sleeping for Java8. No idea why. Works without in Java12
		try{
			Thread.sleep(100);
		} catch (Exception e){
			e.printStackTrace();
			System.exit(1);
		}
		Random rand = new Random();
		int num = rand.nextInt(1);
		if (num == 0){
			playerOneTurn();
		} else {
			playerTwoTurn();
		}
	}

	private void handleDamage(ServerSideConnection attacker, ServerSideConnection defender, int move){
		PokemonMove attack = attacker.getPokemon().getMoves().get(move);
		int dmg = attack.getAttack();
		boolean didCrit = attack.didCrit();
		defender.getPokemon().setHp(defender.getPokemon().getHp()-dmg);
		System.out.println(attacker.getPlayerName() + "'s " + attacker.getPokemon().getName() + " did " + dmg + " to " + defender.getPlayerName() + "'s " + defender.getPokemon().getName() + " using " + move + (didCrit?" It was Super Effective!":""));
		if(defender.getPokemon().getHp() <= 0){
			attacker.sendMessageToPlayer("YOU_WIN "+defender.getPokemon().getId()+"_"+move+"_"+dmg+"_"+(didCrit?"1":"0"));
			defender.sendMessageToPlayer("YOU_LOSE "+attacker.getPokemon().getId()+"_"+move+"_"+dmg+"_"+(didCrit?"1":"0"));
			System.out.println("Game Over! Winner: " + attacker.getPlayerName() + " with their " + attacker.getPokemon().getName()+ ".");
			System.exit(0);
		} else {
			attacker.sendMessageToPlayer("DEAL_DAMAGE "+defender.getPokemon().getId()+"_"+move+"_"+dmg+"_"+(didCrit?"1":"0"));
			defender.sendMessageToPlayer("RECEIVE_DAMAGE "+attacker.getPokemon().getId()+"_"+move+"_"+dmg+"_"+(didCrit?"1":"0"));
		}
	}

	private void playerOneTurn(){
		this.playerOne.sendMessageToPlayer("YOUR_TURN " + ++roundCount);
		this.stateHandler("PLAYER_1_TURN", "", this.playerOne);
	}

	private void playerTwoTurn(){
		this.playerTwo.sendMessageToPlayer("YOUR_TURN " + ++roundCount);
		this.stateHandler("PLAYER_2_TURN", "", this.playerTwo);
	}

	public void onMessageFromPlayer(String msg, ServerSideConnection scc){
		//Whenever we get a message from a player, we'll decode it here.
		//Messages from players will come in very similar to the client
		//But it will be identified by the Socket/ServerSideConnection that it
		//came from:
		String message = msg.split(" ")[0];
		String option = msg.split(" ")[1];
		if (message.equals("NAME_REG")) { //name registration
//			System.out.println("Name Accept: ");
			scc.setPlayerName(option);
			scc.sendMessageToPlayer("NAME_ACCEPT " + option);
		} else {
			stateHandler(message, option, scc);
		}
	}


	public void acceptConnections() throws InterruptedException{
		try {
			System.out.println("Awaiting connection...");
			while (playerCount < 2) {
				Socket s = socket.accept();
				playerCount++;
				System.out.println("Player #" + playerCount + " connected.");
				ServerSideConnection ssc = new ServerSideConnection(s, playerCount, this);

				// assign correct ssc to correct field.
				if (playerCount == 1)
					playerOne = ssc;
				else
					playerTwo = ssc;
				Thread thread = new Thread(ssc);
				thread.start();
			}
			System.out.println(playerCount + " player(s) joined.");
			Thread.sleep(1000); 	//Wait one second to start the game to ensure
										//client caught the NAME_ACCEPT
			sendPokemonToUsers(); //Send them the pokemon so they can make their teams.
		} catch (IOException ex) {  System.out.println("IO Exception from acceptConnection()."); }
	}

	private void sendPokemonToUsers(){
		this.playerOne.sendPokemonListToPlayers(this.pokemonList);
		this.playerTwo.sendPokemonListToPlayers(this.pokemonList);
	}

	public static void main(String[] args) {
		Server server = new Server();
		try {
			server.acceptConnections();
		} catch (Exception e){
			e.printStackTrace();
		}
	}



}
