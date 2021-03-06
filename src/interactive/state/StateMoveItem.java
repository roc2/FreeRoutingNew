/*
 *  Copyright (C) 2014  Alfons Wirtz  
 *   website www.freerouting.net
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License at <http://www.gnu.org/licenses/> 
 *   for more details.
 *
 * MoveComponentState.java
 *
 * Created on 11. Mai 2005, 06:34
 */

package interactive.state;

import interactive.Actlog;
import interactive.IteraBoard;
import interactive.LogfileScope;
import java.awt.Graphics;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Set;
import java.util.TreeSet;
import board.BrdComponents;
import board.BrdLayerStructure;
import board.RoutingBoard;
import board.infos.BrdComponent;
import board.infos.BrdItemViolation;
import board.items.BrdAbitVia;
import board.items.BrdAreaConduction;
import board.items.BrdItem;
import freert.library.BrdLibrary;
import freert.planar.PlaPointFloat;
import freert.planar.PlaPointInt;
import freert.planar.PlaVectorInt;
import gui.varie.GuiResources;
import gui.varie.IteraNetItems;

/**
 *
 * @author Alfons Wirtz
 */
public class StateMoveItem extends StateInteractive
   {
   private final Set<BrdItem> item_list;
   private final Set<BrdComponent> component_list;
   // In case of a component grid the first component is aligned to this grid.
   private final BrdComponent grid_snap_component;

   private PlaPointInt current_position;
   private PlaPointInt previous_position;

   private Collection<BrdItemViolation> clearance_violations;

   private final Collection<IteraNetItems> net_items_list;
   
   /**
    * Returns a new instance or null, if the items of p_itemlist do not belong to a single component.
    */
   public static StateMoveItem get_instance(PlaPointFloat p_location, Collection<BrdItem> p_item_list, StateInteractive p_parent_state, IteraBoard i_board, Actlog p_logfile)
      {
      GuiResources resources = i_board.newGuiResources("interactive.resources.InteractiveState");
      
      if (p_item_list.isEmpty())
         {
         i_board.screen_messages.set_status_message(resources.getString("move_component_failed_because_no_item_selected"));
         return null;
         }
      
      // extend p_item_list to full components
      Set<BrdItem> item_list = new TreeSet<BrdItem>();
      Set<BrdComponent> component_list = new TreeSet<BrdComponent>();
      RoutingBoard routing_board = i_board.get_routing_board();
      BrdComponent grid_snap_component = null;
      
      for (BrdItem curr_item : p_item_list)
         {
         if (curr_item.get_component_no() > 0)
            {
            BrdComponent curr_component = routing_board.brd_components.get(curr_item.get_component_no());
            if (curr_component == null)
               {
               System.out.println("MoveComponentState.get_instance inconsistant component number");
               return null;
               }
            
            if (grid_snap_component == null && (i_board.itera_settings.horizontal_component_grid > 0 || i_board.itera_settings.horizontal_component_grid > 0))
               {
               grid_snap_component = curr_component;
               }
            
            if (!component_list.contains(curr_component))
               {
               Collection<BrdItem> component_items = routing_board.get_component_items(curr_component.id_no);
               for (BrdItem curr_component_item : component_items)
                  {
                  component_list.add(curr_component);
                  item_list.add(curr_component_item);
                  }
               }
            }
         else
            {
            item_list.add(curr_item);
            }
         }
      
      Set<BrdItem> fixed_items = new TreeSet<BrdItem>();
      Set<BrdItem> obstacle_items = new TreeSet<BrdItem>();
      Set<BrdItem> add_items = new TreeSet<BrdItem>();
      boolean move_ok = true;
      
      for (BrdItem curr_item : item_list)
         {
         if (curr_item.is_user_fixed())
            {
            i_board.screen_messages.set_status_message(resources.getString("some_items_cannot_be_moved_because_they_are_fixed"));
            move_ok = false;
            obstacle_items.add(curr_item);
            fixed_items.add(curr_item);
            }
         else if (curr_item.is_connected())
            {
            // Check if the whole connected set is inside the selected items,
            // and add the items of the connected set to the move list in this case.
            // Conduction areas are ignored, because otherwise components with
            // pins contacted to a plane could never be moved.
            boolean item_movable = true;
            Collection<BrdItem> contacts = curr_item.get_connected_set(-1, true);
               {
               for (BrdItem curr_contact : contacts)
                  {
                  if (curr_contact instanceof BrdAreaConduction) continue;

                  if (curr_contact.is_user_fixed())
                     {
                     item_movable = false;
                     fixed_items.add(curr_contact);
                     }
                  else if (curr_contact.get_component_no() != 0)
                     {
                     BrdComponent curr_component = routing_board.brd_components.get(curr_contact.get_component_no());
                     if (!component_list.contains(curr_component))
                        {
                        item_movable = false;
                        }
                     }
                  
                  if (item_movable)
                     add_items.add(curr_contact);
                  else
                     obstacle_items.add(curr_contact);
                  }
               }

            if (!item_movable) move_ok = false;
            }
         }
      
      if (!move_ok)
         {
         if (p_parent_state instanceof StateSelectedItem)
            {
            if (fixed_items.size() > 0)
               {
               ((StateSelectedItem) p_parent_state).get_item_list().addAll(fixed_items);
               i_board.screen_messages.set_status_message(resources.getString("please_unfix_selected_items_before_moving"));
               }
            else
               {
               ((StateSelectedItem) p_parent_state).get_item_list().addAll(obstacle_items);
               i_board.screen_messages.set_status_message(resources.getString("please_unroute_or_extend_selection_before_moving"));
               }
            }
         return null;
         }
      
      item_list.addAll(add_items);
      
      return new StateMoveItem(p_location, item_list, component_list, grid_snap_component, p_parent_state.return_state, i_board, p_logfile);
      }

   private StateMoveItem(
         PlaPointFloat p_location, 
         Set<BrdItem> p_item_list, 
         Set<BrdComponent> p_component_list, 
         BrdComponent p_first_component, 
         StateInteractive p_parent_state, 
         IteraBoard p_board_handling,
         Actlog p_logfile)
      {
      super(p_parent_state, p_board_handling, p_logfile);
      
      component_list = p_component_list;
      grid_snap_component = p_first_component;
      current_position = p_location.round();
      
      previous_position = current_position;
      
      actlog_start_scope(LogfileScope.MOVE_ITEMS, p_location);
      
      RoutingBoard routing_board = r_brd;

      r_brd.start_notify_observers();
      
      // make the situation restorable by undo
      routing_board.generate_snapshot();

      for (BrdItem curr_item : p_item_list)
         {
         routing_board.remove_item(curr_item);
         }
      
      net_items_list = new LinkedList<IteraNetItems>();
      item_list = new TreeSet<BrdItem>();

      for (BrdItem curr_item : p_item_list)
         {
         // Copy the items in p_item_list, because otherwise the undo algorithm will not work.
         BrdItem copied_item = curr_item.copy(0);

         for (int index = 0; index < curr_item.net_count(); ++index)
            {
            add_to_net_items_list(copied_item, curr_item.get_net_no(index));
            }
         item_list.add(copied_item);
         }
      }

   private void add_to_net_items_list(BrdItem p_item, int p_net_no)
      {
      for (IteraNetItems curr_items : net_items_list)
         {
         if (curr_items.net_no == p_net_no)
            {
            // list for p_net_no exists already
            curr_items.items.add(p_item);
            return;
            }
         }
      Collection<BrdItem> new_item_list = r_brd.get_connectable_items(p_net_no);
      new_item_list.add(p_item);
      IteraNetItems new_net_items = new IteraNetItems(p_net_no, new_item_list);
      net_items_list.add(new_net_items);
      }

   public StateInteractive mouse_moved()
      {
      super.mouse_moved();
      
      move(i_brd.get_current_mouse_position());

      actlog_add_corner(current_position.to_float());

      return this;
      }

   public StateInteractive process_logfile_point(PlaPointFloat p_point)
      {
      move(p_point);
      return this;
      }

   public StateInteractive left_button_clicked(PlaPointFloat p_location)
      {
      return complete();
      }

   @Override
   public StateInteractive complete()
      {
      for (BrdItem curr_item : item_list)
         {
         if (curr_item.clearance_violation_count() > 0)
            {
            i_brd.screen_messages.set_status_message(resources.getString("insertion_failed_because_of_obstacles"));
            return this;
            }
         }
      
      for (BrdItem curr_item : item_list)
         {
         r_brd.insert_item(curr_item);
         }

      // let the observers syncronize the moving
      for (BrdComponent curr_component : component_list)
         {
         r_brd.observers.notify_moved(curr_component);
         }

      for (IteraNetItems curr_net_items : net_items_list)
         {
         i_brd.update_ratsnest(curr_net_items.net_no);
         }

      actlog_start_scope(LogfileScope.COMPLETE_SCOPE);

      i_brd.screen_messages.set_status_message(resources.getString("move_completed"));
      i_brd.repaint();
      return return_state;
      }

   public StateInteractive cancel()
      {
      r_brd.undo(null);
      
      for (IteraNetItems curr_net_items : net_items_list)
         {
         i_brd.update_ratsnest(curr_net_items.net_no);
         }
      
      actlog_start_scope(LogfileScope.CANCEL_SCOPE);

      return return_state;
      }

   @Override
   public StateInteractive mouse_wheel_moved(int p_rotation)
      {
      if (i_brd.itera_settings.zoom_with_wheel)
         {
         super.mouse_wheel_moved(p_rotation);
         }
      else
         {
         rotate_deg(-p_rotation);
         }
      return this;
      }

   /**
    * Changes the position of the items in the list to p_new_location.
    */
   private void move(PlaPointFloat p_new_position)
      {
      current_position = p_new_position.round();
      if (!current_position.equals(previous_position))
         {
         PlaVectorInt translate_vector = current_position.difference_by(previous_position);
         if (grid_snap_component != null)
            {
            translate_vector = adjust_to_placement_grid(translate_vector);
            }
         
         BrdComponents components = r_brd.brd_components;
         for (BrdComponent curr_component : component_list)
            {
            components.move(curr_component.id_no, translate_vector);
            }
         clearance_violations = new LinkedList<BrdItemViolation>();
         for (BrdItem curr_item : item_list)
            {
            curr_item.translate_by(translate_vector);
            clearance_violations.addAll(curr_item.clearance_violations());
            }
         previous_position = current_position;
         for (IteraNetItems curr_net_items : net_items_list)
            {
            i_brd.update_ratsnest(curr_net_items.net_no, curr_net_items.items);
            }
         i_brd.repaint();
         }
      }

   private PlaVectorInt adjust_to_placement_grid(PlaVectorInt p_vector)
      {
      PlaPointInt new_component_location = grid_snap_component.get_location().translate_by(p_vector);
      PlaPointInt rounded_component_location = new_component_location.to_float().round_to_grid(i_brd.itera_settings.horizontal_component_grid, i_brd.itera_settings.vertical_component_grid);
      PlaVectorInt adjustment = rounded_component_location.difference_by(new_component_location);
      PlaVectorInt result = p_vector.add(adjustment);
      current_position = previous_position.translate_by(result);
      return p_vector.add(adjustment);
      }

   /**
    * Turns the items in the list by p_factor times 90 degree around the current position.
    */
   public void rotate_90_degree(int p_factor)
      {
      if (p_factor == 0) return;

      BrdComponents components = r_brd.brd_components;
      for (BrdComponent curr_component : component_list)
         {
         components.rotate_90_deg(curr_component.id_no, p_factor, current_position);
         }
      
      clearance_violations = new LinkedList<BrdItemViolation>();
      for (BrdItem curr_item : item_list)
         {
         curr_item.rotate_90_deg(p_factor, current_position);
         clearance_violations.addAll(curr_item.clearance_violations());
         }
      for (IteraNetItems curr_net_items : net_items_list)
         {
         i_brd.update_ratsnest(curr_net_items.net_no, curr_net_items.items);
         }

      actlog_start_scope(LogfileScope.TURN_90_DEGREE, p_factor);
      i_brd.repaint();
      }

   public void rotate_deg(int p_angle_in_degree)
      {
      if (p_angle_in_degree == 0) return;

      BrdComponents components = r_brd.brd_components;
      for (BrdComponent curr_component : component_list)
         {
         components.rotate_deg(curr_component.id_no, p_angle_in_degree, current_position);
         }
      clearance_violations = new LinkedList<BrdItemViolation>();
      PlaPointFloat float_position = current_position.to_float();
      for (BrdItem curr_item : item_list)
         {
         curr_item.rotate_deg(p_angle_in_degree, float_position);
         clearance_violations.addAll(curr_item.clearance_violations());
         }
      for (IteraNetItems curr_net_items : net_items_list)
         {
         i_brd.update_ratsnest(curr_net_items.net_no, curr_net_items.items);
         }

      actlog_start_scope(LogfileScope.ROTATE, (int) p_angle_in_degree);
      i_brd.repaint();
      }

   /**
    * Turns the items in the list by p_factor times 90 degree around the current position.
    */
   public void rotate_45_deg(int p_factor)
      {
      if (p_factor % 2 == 0)
         {
         rotate_90_degree(p_factor / 2);
         }
      else
         {
         rotate_deg(p_factor * 45);
         }
      }

   /**
    * Changes the placement side of the items in the list.
    */
   public void change_placement_side()
      {
      // Check, that all items can be mirrored
      BrdLayerStructure layer_structure = r_brd.layer_structure;
      BrdLibrary board_library = r_brd.brd_library;
      boolean placement_side_changable = true;
      for (BrdItem curr_item : item_list)
         {
         if (curr_item instanceof BrdAbitVia)
            {
            if (board_library.get_mirrored_via_padstack(((BrdAbitVia) curr_item).get_padstack()) == null)
               {
               placement_side_changable = false;
               break;
               }
            }
         else if (curr_item.first_layer() == curr_item.last_layer())
            {
            int new_layer_no = i_brd.get_layer_count() - curr_item.first_layer() - 1;

            if (!layer_structure.is_signal(new_layer_no))
               {
               placement_side_changable = false;
               break;
               }
            }

         }
      if (!placement_side_changable)
         {
         i_brd.screen_messages.set_status_message(resources.getString("cannot_change_placement_side"));
         return;
         }

      board.BrdComponents components = r_brd.brd_components;
      for (BrdComponent curr_component : component_list)
         {
         components.change_side(curr_component.id_no, current_position);
         }
      clearance_violations = new LinkedList<BrdItemViolation>();
      for (BrdItem curr_item : item_list)
         {
         curr_item.change_placement_side(current_position);
         clearance_violations.addAll(curr_item.clearance_violations());
         }
      for (IteraNetItems curr_net_items : net_items_list)
         {
         i_brd.update_ratsnest(curr_net_items.net_no, curr_net_items.items);
         }

      actlog_start_scope(LogfileScope.CHANGE_PLACEMENT_SIDE);
      i_brd.repaint();
      }

   public void reset_rotation()
      {
      BrdComponent component_to_reset = null;
      for (BrdComponent curr_component : component_list)
         {
         if (component_to_reset == null)
            {
            component_to_reset = curr_component;
            }
         else if (component_to_reset.get_rotation_in_degree() != curr_component.get_rotation_in_degree())
            {
            i_brd.screen_messages.set_status_message(resources.getString("unable_to_reset_components_with_different_rotations"));
            return;
            }
         }
      if (component_to_reset == null)
         {
         return;
         }
      int rotation = component_to_reset.get_rotation_in_degree();
      if (!r_brd.brd_components.get_flip_style_rotate_first() || component_to_reset.is_on_front())
         {
         rotation = 360 - rotation;
         }
      rotate_deg(rotation);
      }

   /**
    * Action to be taken when a key is pressed (Shortcut).
    */
   public StateInteractive key_typed(char p_key_char)
      {
      StateInteractive curr_return_state = this;
      if (p_key_char == '+')
         {
         rotate_90_degree(1);
         }
      else if (p_key_char == '*')
         {
         rotate_90_degree(2);
         }
      else if (p_key_char == '-')
         {
         rotate_90_degree(3);
         }
      else if (p_key_char == '/')
         {
         change_placement_side();
         }
      else if (p_key_char == 'r')
         {
         i_brd.itera_settings.set_zoom_with_wheel(false);
         }
      else if (p_key_char == 'z')
         {
         i_brd.itera_settings.set_zoom_with_wheel(true);
         }
      else
         {
         curr_return_state = super.key_typed(p_key_char);
         }
      return curr_return_state;
      }

   public javax.swing.JPopupMenu get_popup_menu()
      {
      return i_brd.get_panel().popup_menu_move;
      }

   public String get_help_id()
      {
      return "MoveItemState";
      }

   public void draw( Graphics p_graphics)
      {
      if (item_list == null) return;

      for (BrdItem curr_item : item_list)
         {
         curr_item.draw(p_graphics, i_brd.gdi_context);
         }

      if ( clearance_violations != null)
         {
         java.awt.Color draw_color = i_brd.gdi_context.get_violations_color();
         for (BrdItemViolation curr_violation : clearance_violations)
            {
            i_brd.gdi_context.fill_area(curr_violation.shape, p_graphics, draw_color, 1);
            }
         }
      }

   }
