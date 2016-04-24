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
 * ForcedPadAlgo.java
 *
 * Created on 1. September 2003, 08:28
 */

package board.algo;

import java.util.Collection;
import board.BrdFromSide;
import board.BrdShapeAndFromSide;
import board.RoutingBoard;
import board.items.BrdAbitPin;
import board.items.BrdAbitVia;
import board.items.BrdItem;
import board.items.BrdTrace;
import board.items.BrdTracePolyline;
import board.shape.ShapeSearchTree;
import board.shape.ShapeTraceEntries;
import board.varie.BrdStopConnection;
import board.varie.ShoveDrillResult;
import freert.planar.PlaDirection;
import freert.planar.PlaLineInt;
import freert.planar.PlaPoint;
import freert.planar.PlaPointFloat;
import freert.planar.PlaPointInt;
import freert.planar.PlaVector;
import freert.planar.Polyline;
import freert.planar.ShapeTile;
import freert.planar.ShapeTileBox;
import freert.planar.ShapeTileOctagon;
import freert.varie.TimeLimit;

/**
 * Class with functions for checking and inserting pads with eventually shoving aside obstacle traces.
 *
 * @author Alfons Wirtz
 */
public final class AlgoShovePad
   {
   private final RoutingBoard r_board;
   
   public AlgoShovePad(RoutingBoard p_board)
      {
      r_board = p_board;
      }

   /**
    * Checks, if possible obstacle traces can be shoved aside, so that a pad with the input parameters can be inserted without
    * clearance violations. Returns false, if the check failed. If p_ignore_items != null, items in this list are not checked, If
    * p_check_only_front only trace obstacles in the direction from p_from_side are checked for performance reasons. This is the
    * cave when moving drill_items
    */
   public ShoveDrillResult check_forced_pad(ShapeTile p_pad_shape, BrdFromSide p_from_side, int p_layer, int[] p_net_no_arr, int p_cl_type, boolean p_copper_sharing_allowed,
         Collection<BrdItem> p_ignore_items, int p_max_recursion_depth, int p_max_via_recursion_depth, boolean p_check_only_front, TimeLimit p_time_limit)
      {
      if (!p_pad_shape.is_contained_in(r_board.get_bounding_box()))
         {
         r_board.set_shove_failing_obstacle(r_board.get_outline());
         return ShoveDrillResult.NOT_DRILLABLE;
         }
      ShapeSearchTree search_tree = this.r_board.search_tree_manager.get_default_tree();
      ShapeTraceEntries shape_entries = new ShapeTraceEntries(p_pad_shape, p_layer, p_net_no_arr, p_cl_type, p_from_side, r_board);
      Collection<BrdItem> obstacles = search_tree.find_overlap_items_with_clearance(p_pad_shape, p_layer, new int[0], p_cl_type);

      if (p_ignore_items != null)
         {
         obstacles.removeAll(p_ignore_items);
         }
      boolean obstacles_shovable = shape_entries.store_items(obstacles, true, p_copper_sharing_allowed);
      if (!obstacles_shovable)
         {
         this.r_board.set_shove_failing_obstacle(shape_entries.get_found_obstacle());
         return ShoveDrillResult.NOT_DRILLABLE;
         }

      // check, if the obstacle vias can be shoved

      for (BrdAbitVia curr_shove_via : shape_entries.shove_via_list)
         {
         if (p_max_via_recursion_depth <= 0)
            {
            this.r_board.set_shove_failing_obstacle(curr_shove_via);
            return ShoveDrillResult.NOT_DRILLABLE;
            }
         PlaPointInt[] new_via_center = r_board.move_drill_algo.try_shove_via_points(p_pad_shape, p_layer, curr_shove_via, p_cl_type, false);

         if (new_via_center.length <= 0)
            {
            this.r_board.set_shove_failing_obstacle(curr_shove_via);
            return ShoveDrillResult.NOT_DRILLABLE;
            }
         PlaVector delta = new_via_center[0].difference_by(curr_shove_via.get_center());
         Collection<BrdItem> ignore_items = new java.util.LinkedList<BrdItem>();
         if (!r_board.move_drill_algo.check(curr_shove_via, delta, p_max_recursion_depth, p_max_via_recursion_depth - 1, ignore_items, p_time_limit))
            {
            return ShoveDrillResult.NOT_DRILLABLE;
            }
         }
      ShoveDrillResult result = ShoveDrillResult.DRILLABLE;
      if (p_copper_sharing_allowed)
         {
         for (BrdItem curr_obstacle : obstacles)
            {
            if (curr_obstacle instanceof BrdAbitPin)
               {
               result = ShoveDrillResult.DRILLABLE_WITH_ATTACH_SMD;
               break;
               }
            }
         }
      int trace_piece_count = shape_entries.substitute_trace_count();
      if (trace_piece_count == 0)
         {
         return result;
         }
      if (p_max_recursion_depth <= 0)
         {
         this.r_board.set_shove_failing_obstacle(shape_entries.get_found_obstacle());
         return ShoveDrillResult.NOT_DRILLABLE;
         }
      if (shape_entries.stack_depth() > 1)
         {
         this.r_board.set_shove_failing_obstacle(shape_entries.get_found_obstacle());
         return ShoveDrillResult.NOT_DRILLABLE;
         }

      boolean is_orthogonal_mode = p_pad_shape instanceof ShapeTileBox;
      for (;;)
         {
         BrdTracePolyline curr_substitute_trace = shape_entries.next_substitute_trace_piece();
         if (curr_substitute_trace == null)
            {
            break;
            }
         for (int i = 0; i < curr_substitute_trace.tile_shape_count(); ++i)
            {
            PlaLineInt curr_line = curr_substitute_trace.polyline().lines_arr[i + 1];
            PlaDirection curr_dir = curr_line.direction();
            boolean is_in_front;
            if (p_check_only_front)
               {
               is_in_front = in_front_of_pad(curr_line, p_pad_shape, p_from_side.side_no, curr_substitute_trace.get_half_width(), true);
               }
            else
               {
               is_in_front = true;
               }
            if (is_in_front)
               {
               BrdShapeAndFromSide curr = new BrdShapeAndFromSide(curr_substitute_trace, i, is_orthogonal_mode, true);
               if (!r_board.shove_trace_algo.check(curr.shape, curr.from_side, curr_dir, p_layer, curr_substitute_trace.net_no_arr, curr_substitute_trace.clearance_class_no(), p_max_recursion_depth - 1,
                     p_max_via_recursion_depth, 0, p_time_limit))
                  {
                  return ShoveDrillResult.NOT_DRILLABLE;
                  }
               }
            }
         }
      return result;
      }

   /**
    * Shoves aside traces, so that a pad with the input parameters can be inserted without clearance violations. Returns false, if
    * the shove failed. In this case the database may be damaged, so that an undo becomes necessesary.
    */
   public boolean forced_pad(ShapeTile p_pad_shape, BrdFromSide p_from_side, int p_layer, int[] p_net_no_arr, int p_cl_type, boolean p_copper_sharing_allowed, Collection<BrdItem> p_ignore_items,
         int p_max_recursion_depth, int p_max_via_recursion_depth)
      {
      if (p_pad_shape.is_empty())
         {
         System.out.println("ShoveTraceAux.forced_pad: p_pad_shape is empty");
         return true;
         }
      if (!p_pad_shape.is_contained_in(r_board.get_bounding_box()))
         {
         this.r_board.set_shove_failing_obstacle(r_board.get_outline());
         return false;
         }
      if (!r_board.move_drill_algo.shove_vias(p_pad_shape, p_from_side, p_layer, p_net_no_arr, p_cl_type, p_ignore_items, p_max_recursion_depth, p_max_via_recursion_depth, false))
         {
         return false;
         }
      ShapeSearchTree search_tree = this.r_board.search_tree_manager.get_default_tree();
      ShapeTraceEntries shape_entries = new ShapeTraceEntries(p_pad_shape, p_layer, p_net_no_arr, p_cl_type, p_from_side, r_board);
      Collection<BrdItem> obstacles = search_tree.find_overlap_items_with_clearance(p_pad_shape, p_layer, new int[0], p_cl_type);
      if (p_ignore_items != null)
         {
         obstacles.removeAll(p_ignore_items);
         }
      boolean obstacles_shovable = shape_entries.store_items(obstacles, true, p_copper_sharing_allowed) && shape_entries.shove_via_list.isEmpty();
      if (!obstacles_shovable)
         {
         this.r_board.set_shove_failing_obstacle(shape_entries.get_found_obstacle());
         return false;
         }
      int trace_piece_count = shape_entries.substitute_trace_count();
      if (trace_piece_count == 0)
         {
         return true;
         }
      if (p_max_recursion_depth <= 0)
         {
         this.r_board.set_shove_failing_obstacle(shape_entries.get_found_obstacle());
         return false;
         }
      boolean tails_exist_before = r_board.contains_trace_tails(obstacles, p_net_no_arr);
      shape_entries.cutout_traces(obstacles);
      boolean is_orthogonal_mode = p_pad_shape instanceof ShapeTileBox;

      for (;;)
         {
         BrdTracePolyline curr_substitute_trace = shape_entries.next_substitute_trace_piece();
         if (curr_substitute_trace == null)
            {
            break;
            }
         if (curr_substitute_trace.first_corner().equals(curr_substitute_trace.last_corner()))
            {
            continue;
            }
         int[] curr_net_no_arr = curr_substitute_trace.net_no_arr;
         for (int i = 0; i < curr_substitute_trace.tile_shape_count(); ++i)
            {
            BrdShapeAndFromSide curr = new BrdShapeAndFromSide(curr_substitute_trace, i, is_orthogonal_mode, false);
            if (!r_board.shove_trace_algo.insert(curr.shape, curr.from_side, p_layer, curr_net_no_arr, curr_substitute_trace.clearance_class_no(), p_ignore_items, p_max_recursion_depth - 1,
                  p_max_via_recursion_depth, 0))
               {
               return false;
               }
            }
         for (int i = 0; i < curr_substitute_trace.corner_count(); ++i)
            {
            r_board.join_changed_area(curr_substitute_trace.polyline().corner_approx(i), p_layer);
            }
         PlaPoint[] end_corners = null;
         if (!tails_exist_before)
            {
            end_corners = new PlaPoint[2];
            end_corners[0] = curr_substitute_trace.first_corner();
            end_corners[1] = curr_substitute_trace.last_corner();
            }
         
         r_board.insert_item(curr_substitute_trace);
         
         ShapeTileOctagon opt_area;
         if (r_board.changed_area != null)
            {
            opt_area = r_board.changed_area.get_area(p_layer);
            }
         else
            {
            opt_area = null;
            }
         curr_substitute_trace.normalize(opt_area);
         if (!tails_exist_before)
            {
            for (int i = 0; i < 2; ++i)
               {
               BrdTrace tail = r_board.get_trace_tail(end_corners[i], p_layer, curr_net_no_arr);
               if (tail != null)
                  {
                  r_board.remove_items_unfixed(tail.get_connection_items(BrdStopConnection.VIA));
                  for (int curr_net_no : curr_net_no_arr)
                     {
                     r_board.combine_traces(curr_net_no);
                     }
                  }
               }
            }
         }
      return true;
      }

   /**
    * Looks for a side of p_shape, so that a trace line from the shape center to the nearest point on this side does not conflict
    * with any obstacles.
    */
   public BrdFromSide calc_from_side(ShapeTile p_shape, PlaPoint p_shape_center, int p_layer, int p_offset, int p_cl_class)
      {
      int[] empty_arr = new int[0];
      ShapeTile offset_shape = (ShapeTile) p_shape.offset(p_offset);
      for (int i = 0; i < offset_shape.border_line_count(); ++i)
         {
         ShapeTile check_shape = calc_check_chape_for_from_side(p_shape, p_shape_center, offset_shape.border_line(i));

         if (r_board.check_trace_shape(check_shape, p_layer, empty_arr, p_cl_class, null))
            {
            return new BrdFromSide(i, null);
            }
         }
      
      // try second check without clearance
      for (int i = 0; i < offset_shape.border_line_count(); ++i)
         {
         ShapeTile check_shape = calc_check_chape_for_from_side(p_shape, p_shape_center, offset_shape.border_line(i));
         if (r_board.check_trace_shape(check_shape, p_layer, empty_arr, 0, null))
            {
            return new BrdFromSide(i, null);
            }
         }
      
      return BrdFromSide.NOT_CALCULATED;
      }

   private static ShapeTile calc_check_chape_for_from_side(ShapeTile p_shape, PlaPoint p_shape_center, PlaLineInt p_border_line)
      {
      PlaPointFloat shape_center = p_shape_center.to_float();
      PlaPointFloat offset_projection = shape_center.projection_approx(p_border_line);
      // Make shure, that direction restrictions are retained.
      PlaLineInt[] line_arr = new PlaLineInt[3];
      PlaDirection curr_dir = p_border_line.direction();
      line_arr[0] = new PlaLineInt(p_shape_center, curr_dir);
      line_arr[1] = new PlaLineInt(p_shape_center, curr_dir.turn_45_degree(2));
      line_arr[2] = new PlaLineInt(offset_projection.round(), curr_dir);
      Polyline check_line = new Polyline(line_arr);
      return check_line.offset_shape(1, 0);
      }

   /**
    * Checks, if p_line is in frone of p_pad_shape when shoving from p_from_side
    */
   private static boolean in_front_of_pad(PlaLineInt p_line, ShapeTile p_pad_shape, int p_from_side, int p_width, boolean p_with_sides)
      {
      if (!p_pad_shape.is_IntOctagon())
         {
         // only implemented for octagons
         return true;
         }
      ShapeTileOctagon pad_octagon = p_pad_shape.bounding_octagon();
      if (!(p_line.point_a instanceof PlaPointInt && p_line.point_b instanceof PlaPointInt))
         {
         // not implemented
         return true;
         }
      PlaPointInt line_a = (PlaPointInt) p_line.point_a;
      PlaPointInt line_b = (PlaPointInt) p_line.point_b;

      double diag_width = p_width * Math.sqrt(2);

      boolean result;
      switch (p_from_side)
         {
         case 0:
            result = Math.min(line_a.v_y, line_b.v_y) >= pad_octagon.oct_uy + p_width || Math.max(line_a.v_x - line_a.v_y, line_b.v_x - line_b.v_y) <= pad_octagon.oct_ulx - diag_width
                  || Math.min(line_a.v_x + line_a.v_y, line_b.v_x + line_b.v_x) >= pad_octagon.oct_urx + diag_width;
            if (p_with_sides && !result)
               {
               result = Math.max(line_a.v_x, line_b.v_x) <= pad_octagon.oct_lx - p_width && Math.min(line_a.v_x - line_a.v_y, line_b.v_x - line_b.v_y) <= pad_octagon.oct_ulx - diag_width
                     || Math.min(line_a.v_x, line_b.v_x) >= pad_octagon.oct_rx + p_width && Math.min(line_a.v_x + line_a.v_y, line_b.v_x + line_b.v_y) >= pad_octagon.oct_urx + diag_width;
               }
            break;
         case 1:
            result = Math.min(line_a.v_y, line_b.v_y) >= pad_octagon.oct_uy + p_width || Math.max(line_a.v_x - line_a.v_y, line_b.v_x - line_b.v_y) <= pad_octagon.oct_ulx - diag_width
                  || Math.max(line_a.v_x, line_b.v_x) <= pad_octagon.oct_lx - p_width;
            if (p_with_sides && !result)
               {
               result = Math.min(line_a.v_x, line_b.v_x) <= pad_octagon.oct_lx - p_width && Math.max(line_a.v_x + line_a.v_y, line_b.v_x + line_b.v_y) <= pad_octagon.oct_llx - diag_width
                     || Math.max(line_a.v_y, line_b.v_y) >= pad_octagon.oct_uy + p_width && Math.min(line_a.v_x + line_a.v_y, line_b.v_x + line_b.v_y) >= pad_octagon.oct_urx + diag_width;
               }
            break;
         case 2:
            result = Math.max(line_a.v_x, line_b.v_x) <= pad_octagon.oct_lx - p_width || Math.max(line_a.v_x - line_a.v_y, line_b.v_x - line_b.v_y) <= pad_octagon.oct_ulx - diag_width
                  || Math.max(line_a.v_x + line_a.v_y, line_b.v_x + line_b.v_y) <= pad_octagon.oct_llx - diag_width;
            if (p_with_sides && !result)
               {
               result = Math.max(line_a.v_y, line_b.v_y) <= pad_octagon.oct_ly - p_width && Math.min(line_a.v_x + line_a.v_y, line_b.v_x + line_b.v_y) <= pad_octagon.oct_llx - diag_width
                     || Math.min(line_a.v_y, line_b.v_y) >= pad_octagon.oct_uy + p_width && Math.min(line_a.v_x - line_a.v_y, line_b.v_x - line_b.v_y) <= pad_octagon.oct_ulx - diag_width;

               }
            break;
         case 3:
            result = Math.max(line_a.v_x, line_b.v_x) <= pad_octagon.oct_lx - p_width || Math.max(line_a.v_y, line_b.v_y) <= pad_octagon.oct_ly - p_width
                  || Math.max(line_a.v_x + line_a.v_y, line_b.v_x + line_b.v_y) <= pad_octagon.oct_llx - diag_width;
            if (p_with_sides && !result)
               {
               result = Math.min(line_a.v_y, line_b.v_y) <= pad_octagon.oct_ly - p_width && Math.min(line_a.v_x - line_a.v_y, line_b.v_x - line_b.v_y) >= pad_octagon.oct_lrx + diag_width
                     || Math.min(line_a.v_x, line_b.v_x) <= pad_octagon.oct_lx - p_width && Math.max(line_a.v_x - line_a.v_y, line_b.v_x - line_b.v_y) <= pad_octagon.oct_ulx - diag_width;
               }
            break;
         case 4:
            result = Math.max(line_a.v_y, line_b.v_y) <= pad_octagon.oct_ly - p_width || Math.max(line_a.v_x + line_a.v_y, line_b.v_x + line_b.v_y) <= pad_octagon.oct_llx - diag_width
                  || Math.min(line_a.v_x - line_a.v_y, line_b.v_x - line_b.v_y) >= pad_octagon.oct_lrx + diag_width;
            if (p_with_sides && !result)
               {
               result = Math.min(line_a.v_x, line_b.v_x) >= pad_octagon.oct_rx + p_width && Math.max(line_a.v_x - line_a.v_y, line_b.v_x - line_b.v_y) >= pad_octagon.oct_lrx + diag_width
                     || Math.max(line_a.v_x, line_b.v_x) <= pad_octagon.oct_lx - p_width && Math.min(line_a.v_x + line_a.v_y, line_b.v_x + line_b.v_y) <= pad_octagon.oct_llx - diag_width;
               }
            break;
         case 5:
            result = Math.max(line_a.v_y, line_b.v_y) <= pad_octagon.oct_ly - p_width || Math.min(line_a.v_x, line_b.v_x) >= pad_octagon.oct_rx + p_width
                  || Math.min(line_a.v_x - line_a.v_y, line_b.v_x - line_b.v_y) >= pad_octagon.oct_lrx + diag_width;
            if (p_with_sides && !result)
               {
               result = Math.max(line_a.v_x, line_b.v_x) >= pad_octagon.oct_rx + p_width && Math.min(line_a.v_x + line_a.v_y, line_b.v_x + line_b.v_y) >= pad_octagon.oct_urx + diag_width
                     || Math.min(line_a.v_y, line_b.v_y) <= pad_octagon.oct_ly - p_width && Math.max(line_a.v_x + line_a.v_y, line_b.v_x + line_b.v_y) <= pad_octagon.oct_llx - diag_width;
               }
            break;
         case 6:
            result = Math.min(line_a.v_x, line_b.v_x) >= pad_octagon.oct_rx + p_width || Math.min(line_a.v_x + line_a.v_y, line_b.v_x + line_b.v_y) >= pad_octagon.oct_urx + diag_width
                  || Math.min(line_a.v_x - line_a.v_y, line_b.v_x - line_b.v_y) >= pad_octagon.oct_lrx + diag_width;
            if (p_with_sides && !result)
               {
               result = Math.max(line_a.v_y, line_b.v_y) <= pad_octagon.oct_ly - p_width && Math.max(line_a.v_x - line_a.v_y, line_b.v_x - line_b.v_y) >= pad_octagon.oct_lrx + diag_width
                     || Math.min(line_a.v_y, line_b.v_y) >= pad_octagon.oct_uy + p_width && Math.max(line_a.v_x + line_a.v_y, line_b.v_x + line_b.v_y) >= pad_octagon.oct_urx + diag_width;
               }
            break;
         case 7:
            result = Math.min(line_a.v_y, line_b.v_y) >= pad_octagon.oct_uy + p_width || Math.min(line_a.v_x + line_a.v_y, line_b.v_x + line_b.v_y) >= pad_octagon.oct_urx + diag_width
                  || Math.min(line_a.v_x, line_b.v_x) >= pad_octagon.oct_rx + p_width;
            if (p_with_sides && !result)
               {
               result = Math.max(line_a.v_y, line_b.v_y) >= pad_octagon.oct_uy + p_width && Math.max(line_a.v_x - line_a.v_y, line_b.v_x - line_b.v_y) <= pad_octagon.oct_ulx - diag_width
                     || Math.max(line_a.v_x, line_b.v_x) >= pad_octagon.oct_rx + p_width && Math.min(line_a.v_x - line_a.v_y, line_b.v_x - line_b.v_y) >= pad_octagon.oct_lrx + diag_width;
               }
            break;
         default:
            {
            System.out.println("ForcedPadAlgo.in_front_of_pad: p_from_side out of range");
            result = true;
            }
         }

      return result;
      }
   }