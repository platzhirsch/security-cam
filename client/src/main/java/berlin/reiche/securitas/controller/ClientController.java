package berlin.reiche.securitas.controller;

import berlin.reiche.securitas.Model;

public class ClientController extends Controller {

	private final Model model;

	private ControllerState state;
	
	public ClientController(Model model) {
		this.model = model;
		this.state = new IdleState();
	}
	
	protected void setState(ControllerState state) {
		if (this.state != null) {
			this.state.dispose();
		}
		this.state = state;
	}

	public Model getModel() {
		return model;
	}
	
}
